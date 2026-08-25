export default function handler() {
  return new Response(
    JSON.stringify({
      service: "Safir AI Humanoid Backend",
      status: "ok",
      elevenLabsConfigured: Boolean(process.env.ELEVENLABS_API_KEY),
    }),
    {
      status: 200,
      headers: { "content-type": "application/json; charset=utf-8" },
    },
  );
}
