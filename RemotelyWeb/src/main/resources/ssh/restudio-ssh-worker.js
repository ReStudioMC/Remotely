"use strict";

(async () => {
    try {
        importScripts("wasm_exec.js");
        const go = new Go();
        const response = await fetch("restudio-ssh.wasm", {cache: "no-store", credentials: "same-origin"});
        if (!response.ok) throw new Error("Browser SSH Engine Is Unavailable");
        let result;
        if (typeof WebAssembly.instantiateStreaming === "function") {
            try {
                result = await WebAssembly.instantiateStreaming(response.clone(), go.importObject);
            } catch (ignored) {
                result = await WebAssembly.instantiate(await response.arrayBuffer(), go.importObject);
            }
        } else {
            result = await WebAssembly.instantiate(await response.arrayBuffer(), go.importObject);
        }
        await go.run(result.instance);
    } catch (error) {
        self.postMessage({
            type: "error",
            code: "engine_load_failed",
            message: "Browser SSH Engine Could Not Start"
        });
    }
})();
