import {
  experimental_upgradeWebSocket,
  type WebSocketData,
} from "@vercel/functions";
import { ElevenLabsTtsSession } from "../elevenlabs/ttsWebSocket.js";

type ClientMessage =
  | {
      type: "start";
      voiceId: string;
      modelId?: string;
      outputFormat?: string;
      languageCode?: string;
    }
  | { type: "text"; text: string; flush?: boolean }
  | { type: "keepalive" }
  | { type: "cancel" }
  | { type: "close" };

export async function GET() {
  return experimental_upgradeWebSocket((ws) => {
    let tts: ElevenLabsTtsSession | null = null;
    let chain = Promise.resolve();

    const send = (payload: unknown) => {
      ws.send(JSON.stringify(payload));
    };

    const handleMessage = async (raw: WebSocketData) => {
      const message = JSON.parse(raw.toString()) as ClientMessage;

      switch (message.type) {
        case "start": {
          if (tts) {
            tts.cancel();
          }

          tts = new ElevenLabsTtsSession(
            {
              voiceId: message.voiceId,
              modelId: message.modelId,
              outputFormat: message.outputFormat,
              languageCode: message.languageCode,
              syncAlignment: true,
              inactivityTimeoutSeconds: 60,
            },
            (event) => send(event),
          );

          await tts.connect();
          send({ type: "ready" });
          return;
        }

        case "text":
          if (!tts) throw new Error("TTS session has not been started");
          tts.sendText(message.text, message.flush ?? false);
          return;

        case "keepalive":
          tts?.keepAlive();
          return;

        case "cancel":
          tts?.cancel();
          tts = null;
          send({ type: "cancelled" });
          return;

        case "close":
          tts?.closeAfterFlush();
          return;
      }
    };

    ws.on("message", (raw: WebSocketData) => {
      // Preserve ordering across start/text/flush messages and surface errors
      // without ever leaking the ElevenLabs API key to the client.
      chain = chain
        .then(() => handleMessage(raw))
        .catch((error) => {
          send({
            type: "error",
            message: error instanceof Error ? error.message : "Voice gateway error",
          });
        });
    });

    ws.on("close", () => {
      tts?.cancel();
      tts = null;
    });
  }, {
    maxPayload: 256 * 1024,
  });
}
