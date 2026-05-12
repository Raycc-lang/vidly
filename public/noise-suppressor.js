const FRAME_SIZE = 480;
const RING_SIZE = FRAME_SIZE * 16;
const PCM_SCALE = 32768;

class NoiseSuppressorProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.enabled = true;
        this.ready = false;
        this.failed = false;
        this.instance = null;
        this.exports = null;
        this.memory = null;
        this.heapF32 = null;
        this.heapU8 = null;
        this.state = 0;
        this.inPtr = 0;
        this.outPtr = 0;
        this.inHeap = null;
        this.outHeap = null;

        this.inputRing = new Float32Array(RING_SIZE);
        this.outputRing = new Float32Array(RING_SIZE);
        this.inputRead = 0;
        this.inputWrite = 0;
        this.inputCount = 0;
        this.outputRead = 0;
        this.outputWrite = 0;
        this.outputCount = 0;

        this.port.onmessage = (event) => {
            const data = event.data || {};
            if (data.type === 'set-enabled') {
                this.enabled = Boolean(data.enabled);
                if (!this.enabled) this.clearRings();
                return;
            }
            if (data.type === 'init') {
                this.initWithWasmModule(data.module);
            }
        };
    }

    initWithWasmModule(module) {
        try {
            if (!(module instanceof WebAssembly.Module)) {
                throw new Error('RNNoise init message did not include a WebAssembly.Module');
            }

            const imports = WebAssembly.Module.imports(module);
            const importObject = this.createImportObject(imports);
            this.instance = new WebAssembly.Instance(module, importObject);
            this.exports = this.instance.exports;

            const rnnoiseCreate = this.getExport(['_rnnoise_create', 'rnnoise_create', 'f']);
            const rnnoiseProcessFrame = this.getExport(['_rnnoise_process_frame', 'rnnoise_process_frame', 'j']);
            const malloc = this.getExport(['_malloc', 'malloc', 'g']);
            const free = this.getExport(['_free', 'free', 'i'], false);
            this.memory = this.getMemoryExport();

            const callCtors = this.getExport(['___wasm_call_ctors', '__wasm_call_ctors', 'd'], false);
            if (typeof callCtors === 'function') callCtors();

            this.heapF32 = new Float32Array(this.memory.buffer);
            this.heapU8 = new Uint8Array(this.memory.buffer);
            this.state = rnnoiseCreate(0);
            this.inPtr = malloc(FRAME_SIZE * Float32Array.BYTES_PER_ELEMENT);
            this.outPtr = malloc(FRAME_SIZE * Float32Array.BYTES_PER_ELEMENT);
            if (!this.state || !this.inPtr || !this.outPtr) {
                if (typeof free === 'function') {
                    if (this.inPtr) free(this.inPtr);
                    if (this.outPtr) free(this.outPtr);
                }
                throw new Error('RNNoise allocation failed');
            }

            this.rnnoiseProcessFrame = rnnoiseProcessFrame;
            this.rnnoiseDestroy = this.getExport(['_rnnoise_destroy', 'rnnoise_destroy', 'h'], false);
            this.free = free;
            this.refreshHeapViews();
            this.ready = true;
            this.failed = false;
            this.clearRings();
            this.port.postMessage({ type: 'ready' });
        } catch (err) {
            this.failed = true;
            this.ready = false;
            this.port.postMessage({ type: 'failed', message: err?.message || String(err) });
        }
    }

    createImportObject(imports) {
        const importObject = {};
        for (const descriptor of imports) {
            if (!importObject[descriptor.module]) importObject[descriptor.module] = {};
            if (descriptor.kind === 'function') {
                importObject[descriptor.module][descriptor.name] = this.createImportFunction(descriptor.module, descriptor.name);
            } else if (descriptor.kind === 'memory') {
                importObject[descriptor.module][descriptor.name] = new WebAssembly.Memory({ initial: 256, maximum: 32768 });
            } else if (descriptor.kind === 'table') {
                importObject[descriptor.module][descriptor.name] = new WebAssembly.Table({ initial: 0, element: 'anyfunc' });
            } else if (descriptor.kind === 'global') {
                importObject[descriptor.module][descriptor.name] = 0;
            }
        }
        return importObject;
    }

    createImportFunction(moduleName, functionName) {
        if ((moduleName === 'a' && functionName === 'a') || functionName === 'emscripten_resize_heap') {
            return (requestedSize) => this.resizeHeap(requestedSize);
        }
        if ((moduleName === 'a' && functionName === 'b') || functionName === 'emscripten_memcpy_js') {
            return (dest, src, num) => {
                this.refreshHeapViews();
                this.heapU8.copyWithin(dest >>> 0, src >>> 0, (src + num) >>> 0);
                return dest;
            };
        }
        return () => 0;
    }

    resizeHeap(requestedSize) {
        if (!this.memory) return 0;
        const currentBytes = this.memory.buffer.byteLength;
        if (requestedSize <= currentBytes) return 1;
        const pagesNeeded = Math.ceil((requestedSize - currentBytes) / 65536);
        try {
            this.memory.grow(pagesNeeded);
            this.refreshHeapViews();
            return 1;
        } catch {
            return 0;
        }
    }

    getExport(names, required = true) {
        for (const name of names) {
            if (typeof this.exports[name] === 'function') return this.exports[name];
        }
        if (required) throw new Error(`RNNoise WASM export missing: ${names.join(' or ')}`);
        return null;
    }

    getMemoryExport() {
        for (const value of Object.values(this.exports)) {
            if (value instanceof WebAssembly.Memory) return value;
        }
        throw new Error('RNNoise WASM memory export missing');
    }

    refreshHeapViews() {
        if (!this.memory) return;
        if (!this.heapF32 || this.heapF32.buffer !== this.memory.buffer) {
            this.heapF32 = new Float32Array(this.memory.buffer);
            this.heapU8 = new Uint8Array(this.memory.buffer);
        }
        if (this.inPtr && this.outPtr) {
            this.inHeap = this.heapF32.subarray(this.inPtr >> 2, (this.inPtr >> 2) + FRAME_SIZE);
            this.outHeap = this.heapF32.subarray(this.outPtr >> 2, (this.outPtr >> 2) + FRAME_SIZE);
        }
    }

    process(inputs, outputs) {
        const input = inputs[0];
        const output = outputs[0];
        const inputChannel = input && input[0];

        if (!output || !output[0]) return true;
        if (!inputChannel || !this.enabled || !this.ready || this.failed) {
            this.copyThrough(input, output);
            return true;
        }

        for (let i = 0; i < inputChannel.length; i++) {
            this.pushInput(inputChannel[i]);
        }

        while (this.inputCount >= FRAME_SIZE) {
            for (let i = 0; i < FRAME_SIZE; i++) {
                this.inHeap[i] = this.shiftInput() * PCM_SCALE;
            }
            this.rnnoiseProcessFrame(this.state, this.outPtr, this.inPtr);
            for (let i = 0; i < FRAME_SIZE; i++) {
                this.pushOutput(Math.max(-1, Math.min(1, this.outHeap[i] / PCM_SCALE)));
            }
        }

        for (let ch = 0; ch < output.length; ch++) {
            const out = output[ch];
            for (let i = 0; i < out.length; i++) {
                out[i] = this.outputCount ? this.shiftOutput() : 0;
            }
        }

        return true;
    }

    clearRings() {
        this.inputRead = 0;
        this.inputWrite = 0;
        this.inputCount = 0;
        this.outputRead = 0;
        this.outputWrite = 0;
        this.outputCount = 0;
    }

    pushInput(sample) {
        this.inputRing[this.inputWrite] = sample;
        this.inputWrite = (this.inputWrite + 1) % RING_SIZE;
        if (this.inputCount < RING_SIZE) this.inputCount++;
        else this.inputRead = (this.inputRead + 1) % RING_SIZE;
    }

    shiftInput() {
        const sample = this.inputRing[this.inputRead];
        this.inputRead = (this.inputRead + 1) % RING_SIZE;
        this.inputCount--;
        return sample;
    }

    pushOutput(sample) {
        this.outputRing[this.outputWrite] = sample;
        this.outputWrite = (this.outputWrite + 1) % RING_SIZE;
        if (this.outputCount < RING_SIZE) this.outputCount++;
        else this.outputRead = (this.outputRead + 1) % RING_SIZE;
    }

    shiftOutput() {
        const sample = this.outputRing[this.outputRead];
        this.outputRead = (this.outputRead + 1) % RING_SIZE;
        this.outputCount--;
        return sample;
    }

    copyThrough(input, output) {
        for (let ch = 0; ch < output.length; ch++) {
            const source = input && (input[ch] || input[0]);
            const target = output[ch];
            if (source) target.set(source);
            else target.fill(0);
        }
    }
}

registerProcessor('noise-suppressor', NoiseSuppressorProcessor);
