package io.github.ethersync

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.diagnostic.telemetry.EDT
import com.intellij.ui.JBColor
import com.intellij.util.io.await
import com.intellij.util.io.awaitExit
import com.intellij.util.io.readLineAsync
import io.github.ethersync.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.Executors

private val LOG = logger<EthersyncServiceImpl>()

@Service(Service.Level.PROJECT)
class EthersyncServiceImpl(
   private val project: Project,
   private val cs: CoroutineScope,
)  : EthersyncService {

   private var launcher: Launcher<RemoteEthersyncClientProtocol>? = null
   private var daemonProcess: Process? = null
   private var clientProcess: Process? = null

   init {
      val bus = project.messageBus.connect()
      bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
         override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            launchDocumentOpenRequest(file.url)
         }

         override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
            launchDocumentCloseNotification(file.url)
         }
      })

      val caretListener = object : CaretListener {
         override fun caretPositionChanged(event: CaretEvent) {
            val uri = event.editor.virtualFile.url
            val pos = Position(event.newPosition.line, event.newPosition.column)
            val range = Range(pos, pos)
            launchCursorRequest(CursorRequest(uri, Collections.singletonList(range)))
         }
      }

      for (editor in FileEditorManager.getInstance(project).allEditors) {
         if (editor is TextEditor) {
            editor.editor.caretModel.addCaretListener(caretListener)
         }
      }

      EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
         override fun editorCreated(event: EditorFactoryEvent) {
            event.editor.caretModel.addCaretListener(caretListener)
         }

         override fun editorReleased(event: EditorFactoryEvent) {
            event.editor.caretModel.removeCaretListener(caretListener)
         }
      }, project)

      ProjectManager.getInstance().addProjectManagerListener(project, object: ProjectManagerListener {
         override fun projectClosingBeforeSave(project: Project) {
            if (clientProcess != null) {
               cs.launch {
                  clientProcess!!.destroy()
                  clientProcess!!.awaitExit()
                  clientProcess = null
               }
            }
            if (daemonProcess != null) {
               cs.launch {
                  daemonProcess!!.destroy()
                  daemonProcess!!.awaitExit()
                  daemonProcess = null
               }
            }
         }
      })
   }

   override fun connectToPeer(peer: String) {
      val projectDirectory = File(project.basePath!!)
      val ethersyncDirectory = File(projectDirectory, ".ethersync")
      val socket = "ethersync-%s-socket".format(project.name)

      cs.launch {
         if (!ethersyncDirectory.exists()) {
            LOG.debug("Creating ethersync directory")
            ethersyncDirectory.mkdir()
         }

         LOG.info("Starting ethersync daemon")
         val daemonProcessBuilder = ProcessBuilder("ethersync", "daemon", "--peer", peer, "--socket-name", socket)
            .directory(projectDirectory)
         daemonProcess = daemonProcessBuilder.start()
         val daemonProcess = daemonProcess!!

         val notifier = project.messageBus.syncPublisher(DaemonOutputNotifier.CHANGE_ACTION_TOPIC)

         cs.launch {
            val stdout = BufferedReader(InputStreamReader(daemonProcess.inputStream))
            stdout.use {
               while (true) {
                  val line = stdout.readLineAsync() ?: break;
                  LOG.trace(line)
                  cs.launch {
                     withContext(Dispatchers.EDT) {
                        notifier.logOutput(line)
                     }
                  }

                  if (line.contains("Others can connect with")) {
                     launchEthersyncClient(socket, projectDirectory)
                  }
               }
            }
         }

         daemonProcess.awaitExit()
         if (daemonProcess.exitValue() != 0) {
            val stderr = BufferedReader(InputStreamReader(daemonProcess.errorStream))
            stderr.use {
               while (true) {
                  val line = stderr.readLineAsync() ?: break;
                  LOG.trace(line)
                  cs.launch {
                     withContext(Dispatchers.EDT) {
                        notifier.logOutput(line)
                     }
                  }
               }
            }

            withContext(Dispatchers.EDT) {
               notifier.logOutput("ethersync exited with exit code: " + daemonProcess.exitValue())
            }
         }
      }
   }

   private fun createProtocolHandler(): EthersyncEditorProtocol {
      val highlighter = HashMap<String, List<RangeHighlighter>>()

      return object : EthersyncEditorProtocol {
         override fun cursor(cursorEvent: CursorEvent) {
            val fileEditorManager = FileEditorManager.getInstance(project)

            val fileEditor = fileEditorManager.allEditors
               .first { editor -> editor.file.url == cursorEvent.documentUri } ?: return

            if (fileEditor is TextEditor) {
               val editor = fileEditor.editor

               cs.launch {
                  withContext(Dispatchers.EDT) {
                     synchronized(highlighter) {
                        val markupModel = editor.markupModel

                        val previous = highlighter.remove(cursorEvent.userId)
                        if (previous != null) {
                           for (hl in previous) {
                              markupModel.removeHighlighter(hl)
                           }
                        }

                        val newHighlighter = LinkedList<RangeHighlighter>()
                        for(range in cursorEvent.ranges) {
                           val startPosition = editor.logicalPositionToOffset(LogicalPosition(range.start.line, range.start.character))
                           val endPosition = editor.logicalPositionToOffset(LogicalPosition(range.end.line, range.end.character))

                           val textAttributes = TextAttributes().apply {
                              // foregroundColor = JBColor(JBColor.YELLOW, JBColor.DARK_GRAY)

                              // TODO: unclear which is the best effect type
                              effectType = EffectType.ROUNDED_BOX
                              effectColor = JBColor(JBColor.YELLOW, JBColor.DARK_GRAY)
                           }

                           val hl = markupModel.addRangeHighlighter(
                              startPosition,
                              endPosition + 1,
                              HighlighterLayer.ADDITIONAL_SYNTAX,
                              textAttributes,
                              HighlighterTargetArea.EXACT_RANGE
                           )
                           if (cursorEvent.name != null) {
                              hl.errorStripeTooltip = cursorEvent.name
                           }

                           newHighlighter.add(hl)
                        }
                        highlighter[cursorEvent.userId] = newHighlighter
                     }
                  }
               }
            }
         }

      }
   }

   private fun launchEthersyncClient(socket: String, projectDirectory: File) {

      cs.launch {

         LOG.info("Starting ethersync client")
         val clientProcessBuilder = ProcessBuilder("ethersync", "client", "--socket-name", socket)
               .directory(projectDirectory)
         clientProcess = clientProcessBuilder.start()
         val clientProcess = clientProcess!!

         val ethersyncEditorProtocol = createProtocolHandler()
         launcher = Launcher.createIoLauncher(
               ethersyncEditorProtocol,
               RemoteEthersyncClientProtocol::class.java,
               clientProcess.inputStream,
               clientProcess.outputStream,
               Executors.newCachedThreadPool(),
               { c -> c },
               { _ -> run {} }
         )

         val listening = launcher!!.startListening()

         val fileEditorManager = FileEditorManager.getInstance(project)
         for (file in fileEditorManager.openFiles) {
            launchDocumentOpenRequest(file.url)
         }

         clientProcess.awaitExit()

         listening.cancel(true)
         listening.await()

         if (clientProcess.exitValue() != 0) {
            val stderr = BufferedReader(InputStreamReader(clientProcess.errorStream))
            stderr.use {
               while (true) {
                  val line = stderr.readLineAsync() ?: break;
                  LOG.trace(line)
                  System.out.println(line)
               }
            }
         }
      }
   }

   fun launchDocumentCloseNotification(fileUri: String) {
      val launcher = launcher ?: return
      cs.launch {
         launcher.remoteProxy.close(DocumentRequest(fileUri))
      }
   }

   fun launchDocumentOpenRequest(fileUri: String) {
      val launcher = launcher ?: return
      cs.launch {
         try {
            launcher.remoteProxy.open(DocumentRequest(fileUri)).await()
         } catch (e: ResponseErrorException) {
            TODO("not yet implemented: notify about an protocol error")
         }
      }
   }

   fun launchCursorRequest(cursorRequest: CursorRequest) {
      val launcher = launcher ?: return
      cs.launch {
         try {
            launcher.remoteProxy.cursor(cursorRequest).await()
         } catch (e: ResponseErrorException) {
            TODO("not yet implemented: notify about an protocol error")
         }
      }
   }
}