package com.bellota.mascota.local;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Selección liviana de respuestas locales con variación y contexto. */
public final class ResponsePack {
    private final Map<String, String[]> responses = new HashMap<>();
    private final Random random = new Random();
    private final Map<String, Integer> last = new HashMap<>();

    public ResponsePack() {
        responses.put("hello", new String[]{"¡Hola! Qué bueno escucharte.", "¡Buenas! Acá estoy.", "Hola, me alegra que estés por acá."});
        responses.put("thanks", new String[]{"¡De nada!", "No hay de qué.", "Me gusta poder ayudarte."});
        responses.put("bye", new String[]{"Hasta luego. Me quedo por acá.", "Nos vemos pronto.", "Que estés bien."});
        responses.put("doing", new String[]{"Estaba mirando alrededor y pensando en cosas.", "Acá ando, explorando un poquito.", "Me entretengo observando lo que pasa."});
        responses.put("name", new String[]{"Me llamo Bellota.", "Soy Bellota, ¡esa soy yo!", "Bellota. Ese es mi nombre."});
        responses.put("unknown", new String[]{"No estoy segura de haber entendido bien. ¿Me lo explicás de otra manera?", "Quiero seguirte la conversación, pero necesito un poquito más de contexto.", "Eso me dejó pensando. ¿Me contás un detalle más?"});
    }

    public synchronized String pick(String key, ChatContext context, String... defaults) {
        String[] options = responses.get(key);
        if (options == null || options.length == 0) options = defaults;
        if (options == null || options.length == 0) return "Te escucho.";
        int index = options.length == 1 ? 0 : random.nextInt(options.length);
        Integer previous = last.get(key);
        if (options.length > 1 && previous != null && index == previous) index = (index + 1 + random.nextInt(options.length - 1)) % options.length;
        last.put(key, index);
        String result = options[index];
        String name = context == null ? null : context.var("nombre");
        if (name != null && !name.trim().isEmpty() && ("hello".equals(key) || "thanks".equals(key))) result = result.replace("¡Hola!", "¡Hola, " + name + "!");
        return result;
    }
}