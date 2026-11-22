# Revisión 4: OpenAI Realtime y WebRTC

## Flujo de conexión

1. Android solicita al Worker una credencial efímera de OpenAI.
2. El Worker configura la sesión con `max_output_tokens=1024` y truncamiento automático.
3. Si están configurados `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`, el Worker obtiene credenciales Cloudflare TURN con TTL de 3.600 segundos y entrega solo las credenciales temporales al APK.
4. Android configura STUN y los servidores TURN recibidos, crea la oferta SDP y negocia contra `/v1/realtime/calls`.
5. El secreto efímero se descarta al aplicar la respuesta SDP remota.

## Límites de sesión

- Duración máxima local: 45 minutos. OpenAI documenta una duración máxima de 60 minutos para sesiones Realtime.
- Máximo de salida configurado: 1.024 tokens por respuesta.
- Tope acumulado local: 24.000 tokens reportados por los eventos `response.done`.
- Truncamiento automático del contexto.

El tope acumulado y la duración son protecciones del cliente. No sustituyen los límites de gasto, alertas de facturación ni cuotas de la cuenta OpenAI. Si la aplicación se modifica o no recibe datos de uso, el tope local no constituye una garantía contable.

## Recuperación y liberación

- Timeout global de negociación: 60 segundos.
- Recolección ICE: se intenta enviar la oferta cuando termina; a los 4 segundos se envía la oferta con los candidatos reunidos hasta ese momento.
- Estado ICE `DISCONNECTED`: período de gracia de 10 segundos.
- Fallo transitorio: un reintento automático; 1,2 segundos de espera general o 5 segundos ante HTTP 429.
- Callbacks asociados a intentos anteriores se descartan por identificador de intento.
- Desconexión idempotente, cancelación de timers, aborto de HTTP SDP, cierre de DataChannel/PeerConnection y liberación de pistas, fuente, fábrica, módulo de audio y ejecutor.

## Fallback actual

`MainActivity` registra OpenAI Realtime como único proveedor online. Si OpenAI falla después del reintento, `VoiceProviderManager` solicita el motor local. Gemini Live todavía no está implementado ni registrado como proveedor secundario; no se debe presentar como fallback disponible.

## Configuración operativa pendiente

1. Crear una TURN key en Cloudflare Realtime.
2. Guardar `TURN_KEY_ID` y `TURN_KEY_API_TOKEN` como secretos del Worker.
3. Desplegar el Worker actualizado.
4. Verificar en una red restrictiva que se selecciona un candidato relay y que el audio funciona en ambos sentidos.
5. Configurar límites y alertas de gasto en OpenAI.

Sin los dos secretos TURN, el Worker devuelve una lista ICE vacía y la app conserva el STUN de Google como compatibilidad. En ese estado no hay relay TURN. Si se configura solo uno de los secretos o Cloudflare no puede emitir credenciales, el Worker responde `503 turn_unavailable` en lugar de degradar silenciosamente.

Cloudflare factura TURN saliente cuando se usa fuera de su Realtime SFU. Revisar la tarifa vigente antes de habilitar el servicio.

## Verificación pendiente

Las pruebas unitarias cubren la política de presupuesto y el parseo de servidores ICE. La CI debe confirmar compilación, Lint y pruebas instrumentales en Android 7/API 24 y Android 12/API 31. La validación de TURN real requiere secretos de Cloudflare y una prueba de red; la negociación real también requiere credenciales OpenAI válidas. No considerar la revisión certificada hasta completar esas pruebas.