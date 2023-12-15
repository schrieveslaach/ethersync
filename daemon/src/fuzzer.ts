import cp from "child_process"
import {attach, NeovimClient} from "neovim"
import {Daemon} from "./daemon"

const PAGE = "fuzzing"

function delay(time: number) {
    return new Promise((resolve) => setTimeout(resolve, time))
}

class Fuzzer {
    // TODO: Give proper types.
    daemon: any = undefined
    nvim: any = undefined

    constructor() {}

    // length is in UTF-16 code units.
    randomString(length: number): string {
        let chars = ["x", "ö", "🥕", "字", " "]
        let result = ""
        while (result.length < length) {
            let char = chars[Math.floor(Math.random() * chars.length)]
            if (result.length + char.length > length) {
                continue
            }
            result += char
        }
        return result
    }

    randomDaemonEdit() {
        let content = this.daemonContent()
        let documentLength = content.length
        if (Math.random() < 0.5) {
            let start = Math.floor(Math.random() * documentLength)
            console.log("documentLength", documentLength)
            let maxDeleteLength = Math.floor(documentLength - start - 1)
            if (maxDeleteLength > 0) {
                let length =
                    1 + Math.floor(Math.random() * (maxDeleteLength - 1))

                console.log(`daemon: delete(${start}, ${length}) in ${content}`)
                this.daemon.findPage(PAGE).get("content").delete(start, length)
            }
        } else {
            let start = Math.floor(Math.random() * documentLength)
            let length = Math.floor(Math.random() * 10)
            let text = this.randomString(length)
            console.log(`daemon: insert(${start}, ${text}) in ${content}`)
            this.daemon.findPage(PAGE).get("content").insert(start, text)
        }
    }

    async randomVimEdit() {
        let content = await this.vimContent()
        let documentLength = content.length
        if (Math.random() < 0.5) {
            let start = Math.floor(Math.random() * documentLength)
            let maxDeleteLength = Math.floor(documentLength - start - 1)
            if (maxDeleteLength > 0) {
                let length =
                    1 + Math.floor(Math.random() * (maxDeleteLength - 1))
                console.log(`editor: delete(${start}, ${length}) in ${content}`)
                await this.nvim.request("nvim_exec_lua", [
                    "require('utils').delete(select(1, ...), select(2, ...))",
                    [start, length],
                ])
            }
        } else {
            let start = Math.floor(Math.random() * documentLength)
            let length = Math.floor(Math.random() * 10)
            let text = this.randomString(length)
            console.log(`editor: insert(${start}, ${text}) in ${content}`)
            await this.nvim.request("nvim_exec_lua", [
                "require('utils').insert(select(1, ...), select(2, ...))",
                [start, text],
            ])
        }
        //await delay(200)
    }

    async vimContent(): Promise<string> {
        return (await this.nvim.buffer.lines).join("\n")
    }

    daemonContent(): string {
        return this.daemon.findPage(PAGE).get("content").toString()
    }

    async run() {
        this.daemon = new Daemon(false)
        await this.daemon.start()

        const nvim_proc = cp.spawn("nvim", ["--embed", "--headless"], {})
        this.nvim = attach({proc: nvim_proc})
        // Allow some wakeup time for vim.
        await delay(500)

        this.daemon.createPage(PAGE, "hello")
        this.daemon.pullAllPages()

        await this.nvim.command(`edit! output/${PAGE}`)

        for (let i = 0; i < 100; i++) {
            if (Math.random() < 0.5) {
                this.randomDaemonEdit()
            } else {
                await this.randomVimEdit()
            }
        }

        await delay(500)

        let vimContent = await this.vimContent()
        let daemonContent = this.daemonContent()
        console.log("vim:", vimContent)
        console.log("daemon:", daemonContent)
        if (vimContent !== daemonContent) {
            console.log("Fuzzing failed!")
        } else {
            console.log("Fuzzing successful!")
        }
        return
    }
}

await new Fuzzer().run()