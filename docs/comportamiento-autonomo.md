# Comportamiento autónomo de Bellota (Fase 1)

Todo el comportamiento está en `core/AutonomousBehavior.java` (decisiones y tiempos) y `MainActivity.java` (ejecución).
Los textos están en `local/PetVoiceLines.java`. Las constantes de abajo se pueden ajustar para afinar el carácter.

## Energía y sueño
| Situación | Regla |
|---|---|
| Despierta | -1 de energía cada 15 min (mínimo 5) |
| Dormida | +1 de energía cada 2 min |
| Energía < 40 | Avisa "Creo que estoy cansada, me voy a dormir" y se duerme a los 8 s |
| Aburrida (etapa 3+, energía < 75) | Puede decir "Estoy aburrida, me voy a descansar" (máx. 1 vez cada 20 min) |
| Noche (0 a 7 h) sin hablarle 10 min | Se va a dormir; despierta a la mañana |
| "No te duermas" | Cancela el sueño y sigue despierta hasta llegar al 5% |
| Energía ≤ 5% | Sueño obligatorio: no se despierta por toques ni voz; mic de XiaoZhi silenciado |
| Despertar | Sueño normal: con ≥ 85% (y mínimo 20 a 30 min). Obligatorio: con ≥ 50% |
| Despertarla a mano | Toque o "Bellota" / "hola"; queda 30 min sin dormirse sola |

Dormida baja el brillo de pantalla al mínimo y no reacciona al ruido ambiente.
Comandos de voz: "no te duermas", "dormite", "andá a dormir", "despertate".

## Escalera del aburrimiento
Minutos sin que le hables ni la toques (se multiplica por 1,4 si está sin ganas y crece con la irritación):

| Etapa | Minutos | Qué hace |
|---|---|---|
| 0 | < 3 | Atenta: mira alrededor, a veces bosteza |
| 1 | 3 a 7 | Ojos aburridos, bosteza, habla sola |
| 2 | 7 a 12 | Habla sola, empieza a jugar, puede iniciar charla |
| 3 | 12 a 20 | Juega sola seguido; puede dormirse por aburrimiento |
| 4 | 20 a 32 | Ojos enojados, se queja |
| 5 | 32+ (hasta 12 min) | Ofendida: mira para otro lado. Después arranca otro ciclo |

Al hablarle o tocarla suavemente en etapa 4 o 5, se contenta (ojos felices, saltito y frase de reconciliación).
Un golpe (toque firme) no cuenta como reconciliación.

## Juegos solos
Pelota que rebota, burbujas, mosca molesta, estrellas fugaces, escondite (los ojos salen de pantalla y se asoman)
y el punto luminoso. Duran 5 a 15 s, con 70 s de espera entre uno y otro. Cualquier toque o voz los corta.
La capa de juego (`ui/PlayfieldView.java`) solo dibuja mientras hay un juego activo.

## Nombre, apodos y gustos
- "Me llamo X" / "mi nombre es X": guarda el nombre.
- "Decime X", "llamame X", "me dicen X", "mi apodo es X": guarda un apodo (hasta 5).
- "Me gusta/encanta X": guarda un gusto. "Ya no me gusta X": lo borra.
- Sin conexión a XiaoZhi, a veces pregunta "¿Seguís con ganas de X?" y entiende sí o no.
  Con XiaoZhi conectado solo hace comentarios (no deja preguntas pendientes que XiaoZhi no puede contestar).
- Mientras habla con su voz local, el micrófono de XiaoZhi se silencia para que no se escuche a sí misma.
