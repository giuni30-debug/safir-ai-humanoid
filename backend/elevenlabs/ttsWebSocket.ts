import WebSocket from "ws";

type Alignment = {
  chars: string[];
  char_start_times_ms: number[];
  char_durations_ms: number[];
};

type ElevenLabsFrame = {
  audio?: string;
  alignment?: Alignment;
  normalizedAlignment?: Alignment;
  is_final?: boolean;
};

export type SafirTtsEvent =
  | { type: "audio"; audioBase64: string; alignment?: Alignment }
  | { type: "final" }
  | { type: "error"; message: string };

export type ElevenLabsTtsOptions = {
  voiceId: string;
  modelId?: string;
  outputFormat?: string;
  languageCode?: string;
  syncAlignment?: boolean;
  inactivityTimeoutSeconds?: number;
};

/**
 * Backend-only ElevenLabs realtime TTS session.
 *
 * Security rule: ELEVENLABS_API_KEY is read only from the backend environment.
 * It must never be sent to or embedded in the Android client.
 */
export class ElevenLabsTtsSession {
  private socket: WebSocket | null = null;
  private closed = false;

  constructor(
    private readonly options: ElevenLabsTtsOptions,
    private readonly emit: (event: SafirTtsEvent) => void,
  ) {}

  async connect(): Promise<void> {
    const apiKey = process.env.ELEVENLABS_API_KEY;
    if (!apiKey) throw new Error("ELEVENLABS_API_KEY is not configured");

    const params = new URLSearchParams({
      model_id: this.options.modelId ?? "eleven_flash_v2_5",
      output_format: this.options.outputFormat ?? "mp3_44100_128",
      sync_alignment: String(this.options.syncAlignment ?? true),
      inactivity_timeout: String(this.options.inactivityTimeoutSeconds ?? 60),
    });

    if (this.options.languageCode) {
      params.set("language_code", this.options.languageCode);
    }

    const url = `wss://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(
      this.options.voiceId,
    )}/stream-input?${params.toString()}`;

    this.socket = new WebSocket(url, {
      headers: { "xi-api-key": apiKey },
    });

    await new Promise<void>((resolve, reject) => {
      const socket = this.socket;
      if (!socket) return reject(new Error("WebSocket not created"));

      socket.once("open", () => {
        socket.send(
          JSON.stringify({
            text: " ",
            generation_config: {
              chunk_length_schedule: [120, 160, 250, 290],
            },
          }),
        );
        resolve();
      });

      socket.on("message", (data) => {
        try {
          const frame = JSON.parse(data.toString()) as ElevenLabsFrame;

          if (frame.audio) {
            this.emit({
              type: "audio",
              audioBase64: frame.audio,
              alignment: frame.alignment ?? frame.normalizedAlignment,
            });
          }

          if (frame.is_final) {
            this.emit({ type: "final" });
          }
        } catch (error) {
          this.emit({
            type: "error",
            message: error instanceof Error ? error.message : "Invalid ElevenLabs frame",
          });
        }
      });

      socket.once("error", (error) => {
        this.emit({ type: "error", message: error.message });
        reject(error);
      });
    });
  }

  sendText(text: string, flush = false): void {
    if (this.closed || !this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("ElevenLabs TTS socket is not open");
    }

    if (!text) return;
    this.socket.send(JSON.stringify({ text, flush }));
  }

  keepAlive(): void {
    if (this.closed || !this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    this.socket.send(JSON.stringify({ text: " " }));
  }

  closeAfterFlush(): void {
    if (this.closed) return;
    this.closed = true;

    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ text: "" }));
    }
  }

  cancel(): void {
    if (this.closed) return;
    this.closed = true;
    this.socket?.close(1000, "barge-in");
  }
}
