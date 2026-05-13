import createRNNWasmModuleSync from './vendor/rnnoise-sync.js';

const FRAME_SIZE = 480;
const RING_SIZE = FRAME_SIZE * 16;
const PCM_SCALE = 32768;

class NoiseSuppressorProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.enabled = true;
        this.ready = false;
        this.failed = false;
        this.inputRing = new Float32Array(RING_SIZE);
        this.outputRing = new Float32Array(RING_SIZE);
        this.inputRead = 0;
        this.inputWrite = 0;
        this.inputCount = 0;
        this.outputRead = 0;
        this.outputWrite = 0;
        this.outputCount = 0;

        this.port.onmessage = (event) => {
            if (event.data && event.data.type === 'set-enabled') {
                this.enabled = Boolean(event.data.enabled);
                if (!this.enabled) {
                    this.clearRings();
                }
            }
        };

        try {
            this.Module = createRNNWasmModuleSync();
            this.state = this.Module._rnnoise_create(0);
            this.inPtr = this.Module._malloc(FRAME_SIZE * Float32Array.BYTES_PER_ELEMENT);
            this.outPtr = this.Module._malloc(FRAME_SIZE * Float32Array.BYTES_PER_ELEMENT);
            this.inHeap = this.Module.HEAPF32.subarray(this.inPtr >> 2, (this.inPtr >> 2) + FRAME_SIZE);
            this.outHeap = this.Module.HEAPF32.subarray(this.outPtr >> 2, (this.outPtr >> 2) + FRAME_SIZE);
            this.ready = Boolean(this.state && this.inPtr && this.outPtr);
            this.port.postMessage({ type: this.ready ? 'ready' : 'failed' });
        } catch (err) {
            this.failed = true;
            this.port.postMessage({ type: 'failed', message: err?.message || String(err) });
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
            this.Module._rnnoise_process_frame(this.state, this.outPtr, this.inPtr);
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
