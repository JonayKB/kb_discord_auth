# Contrato: estadísticas del mod → KB Discord Bot

Versión del contrato: **1**

Este documento describe cómo envía `kb_discord_auth` las estadísticas de los jugadores y qué tiene que hacer KB Discord Bot para guardarlas y publicar una tabla de clasificación en un canal. El mod ya cumple la parte del emisor. La parte del bot es lo que falta por implementar.

## 1. Resumen

- El mod manda **totales acumulados** por jugador (no incrementos). Reenviar lo mismo no cambia nada, y si se pierde un envío el siguiente lo corrige.
- El bot guarda el último total de cada jugador y, para el ranking semanal, una **instantánea al inicio de cada semana**. Semanal = total actual − instantánea.
- Cada servidor de Minecraft se autentica con su propia **API key**. La key identifica el servidor en el bot.

## 2. Endpoint

```
POST {PANEL_URL}/api/integrations/minecraft/stats
Content-Type: application/json
Authorization: Bearer <apiKey>
User-Agent: kb_discord_auth/<versión del mod>
```

- La ruta va **fuera** de la sesión del panel (`/api/*` pide sesión de Discord): este endpoint solo acepta la API key.
- La key se genera en el panel, en la configuración del servidor de Minecraft (módulo `servers`). Se muestra una sola vez, se guarda su SHA-256 y se compara en tiempo constante. Tiene que poder regenerarse, y al regenerarla la anterior deja de valer.
- Si el panel está detrás de nginx/Caddy, el mod usa la URL pública (HTTPS recomendado).

## 3. Cuerpo

```json
{
  "version": 1,
  "server": {
    "id": "46bf8427d5aadb8d5fd070fa87e1792b",
    "name": "KB Survival"
  },
  "sentAt": 1793867421460,
  "players": [
    {
      "uuid": "f3d28cb0-7225-3cb1-baeb-2dadd2be89ae",
      "name": "Tester",
      "discordId": "111111111111111111",
      "online": false,
      "takenAt": 1793867421455,
      "stats": {
        "playTimeSeconds": 912,
        "deaths": 0,
        "mobKills": 0,
        "distanceCm": 451,
        "advancements": 0
      }
    }
  ]
}
```

| Campo | Tipo | Notas |
|---|---|---|
| `version` | entero | Versión del contrato. El bot rechaza con `400` las que no conoce. |
| `server.id` | texto (32 hex) | Id interno del mod (`links.json`). Sirve para los logs. El servidor lo identifica la API key, no este campo. |
| `server.name` | texto, ≤ 64 | `general.serverName` del TOML. El bot lo guarda en cada envío y lo usa como título del ranking. |
| `sentAt` | entero, ms epoch | Hora del envío en el servidor de Minecraft. |
| `players` | lista, 1–500 | El mod manda como mucho 200 por petición. |
| `players[].uuid` | texto UUID | Clave del jugador **dentro de ese servidor**. |
| `players[].name` | texto, 1–16 | Nombre actual de Minecraft. Puede cambiar, así que el bot lo actualiza siempre. En servidores no premium puede tener caracteres raros: escapar el Markdown al mostrarlo. |
| `players[].discordId` | texto o `null` | Discord vinculado con el mod. `null` si el jugador no ha iniciado sesión con Discord nunca (p. ej. se unió antes de instalar el mod). |
| `players[].online` | booleano | `true` en los envíos periódicos y `false` al salir y en la carga inicial. Solo informativo. |
| `players[].takenAt` | entero, ms epoch | Cuándo se leyeron esos valores. Sirve para **descartar datos viejos** (ver 5). |
| `stats.playTimeSeconds` | entero ≥ 0 | Tiempo jugado (estadística `play_time`, en segundos). |
| `stats.deaths` | entero ≥ 0 | Muertes. |
| `stats.mobKills` | entero ≥ 0 | Mobs matados (cualquier mob). |
| `stats.distanceCm` | entero ≥ 0 | Distancia en **centímetros**: andar, agachado, correr, nadar, bajo el agua, sobre el agua, escalar, élitros, barca, caballo, vagoneta, cerdo y strider. No cuenta el vuelo en creativo/espectador ni las caídas. |
| `stats.advancements` | entero ≥ 0 | Logros completados que salen en la pantalla de logros (sin recetas). |

El bot tiene que **ignorar campos desconocidos** dentro de `stats` y del jugador. Así el mod puede añadir estadísticas nuevas sin subir la versión del contrato. Las estadísticas que falten se tratan como "sin dato", no como 0.

## 4. Cuándo envía el mod

| Momento | Qué manda |
|---|---|
| Al arrancar el servidor (`stats.backfillOnStart`) | Los totales guardados (`world/stats`) de todos los jugadores que han entrado alguna vez y de los que se conoce el nombre. |
| Cada `stats.intervalMinutes` (5 por defecto) | Los jugadores conectados. |
| Cuando un jugador sale | Ese jugador. |
| Al parar el servidor | Lo que quede en cola (espera hasta ~8 s). |

Los jugadores que todavía no han iniciado sesión con Discord **no se envían**: en servidores no premium podrían estar usando el nombre de otro.

## 5. Reglas al recibir

1. Comprobar la key → servidor. Si no vale: `401`.
2. Validar el cuerpo completo. Si algo no cuadra: `400` con `{"error": "<motivo>"}` y no guardar nada de esa petición.
3. Para cada jugador, dentro de una sola transacción:
   - Si ya hay datos de `(servidor, uuid)` con un `takenAt` **mayor o igual**, ignorarlo (llegó tarde o repetido).
   - Si no, guardar `name`, `discordId`, `takenAt` y los totales.
4. Responder `200 {"accepted": <n>, "ignored": <m>}`.

Qué hace el mod con cada respuesta:

| Respuesta | El mod |
|---|---|
| `2xx` | Da los datos por entregados. |
| `400`, `413` | Los descarta y lo apunta en el log (reintentar no arreglaría nada). |
| `401`, `403` | Avisa una vez en el log y reintenta en el siguiente envío (por si se corrige la key). |
| `429`, `5xx`, error de red | Reintenta en el siguiente envío. Si mientras tanto hay datos más nuevos de un jugador, se envían esos. |

Límites recomendados: cuerpo ≤ 1 MB, 500 jugadores por petición y unas 30 peticiones por minuto y key.

## 6. Ranking semanal

- La semana empieza el **lunes a las 00:00** en `TIMEZONE` del `.env` del bot.
- Al empezar cada semana (tarea con `croner`), el bot copia los totales actuales de todos los jugadores como **base de la semana**.
- Un jugador sin base esa semana (el primer dato llega a mitad de semana) recibe como base sus primeros totales recibidos. Así empieza en 0, porque no se sabe qué hizo antes.
- `semanal = max(0, total − base)` por estadística.
- Si un total **baja** respecto a la base (un admin borró `world/stats`), esa base pasa a 0 y el semanal es el total nuevo.

## 7. Guardado (sugerencia, SQLite)

```sql
CREATE TABLE mc_stats_keys (
  server_id   INTEGER PRIMARY KEY REFERENCES servers(id),
  key_hash    TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE mc_stats_players (
  server_id   INTEGER NOT NULL,
  uuid        TEXT NOT NULL,
  name        TEXT NOT NULL,
  discord_id  TEXT,
  taken_at    INTEGER NOT NULL,
  stats       TEXT NOT NULL,   -- JSON con los totales
  PRIMARY KEY (server_id, uuid)
);

CREATE TABLE mc_stats_weekly_base (
  server_id   INTEGER NOT NULL,
  week_start  INTEGER NOT NULL, -- ms epoch del lunes 00:00
  uuid        TEXT NOT NULL,
  stats       TEXT NOT NULL,
  PRIMARY KEY (server_id, week_start, uuid)
);
```

Se guarda también `servers.mc_display_name` (o similar) con `server.name` del último envío.

## 8. Tabla de clasificación en Discord

### Configuración en el panel

Por cada servidor de Minecraft se pueden crear uno o varios rankings:

| Opción | Valores | Por defecto |
|---|---|---|
| Canal | canal de texto del servidor de Discord | (obligatorio) |
| Estadísticas | cualquier combinación de las 5 | las 5 |
| Periodo | `total`, `semanal` o los dos | los dos |
| Top | 1–25 jugadores | 10 |
| Actualizar cada | 5–1440 min | 15 |
| Título | texto; `{server}` = nombre del servidor | `🏆 {server}` |
| Solo vinculados | ocultar jugadores sin `discordId` | no |
| Excluir | lista de UUID o nombres (staff, cuentas de prueba) | vacía |

### Publicación

- El bot publica **un mensaje** por ranking y después lo **edita** (guarda el id del mensaje). Si el mensaje ya no existe, lo vuelve a publicar.
- Un embed por estadística, o uno solo con un campo por estadística si caben. Con los dos periodos, columnas "Total" y "Esta semana".
- Cada línea: posición, nombre y valor. Si hay `discordId` y la persona sigue en el servidor de Discord, se muestra `<@discordId>` (en un embed no le avisa) y el nombre de Minecraft entre paréntesis. Si no, solo el nombre de Minecraft.
- Formato de los valores:
  - Tiempo: `3d 4h`, `5h 12m`, `42m`.
  - Distancia: `distanceCm / 100000` en km con un decimal (`12.3 km`), o metros por debajo de 1 km.
  - El resto, el número con separador de miles.
- Pie del embed: "Actualizado <t:unix:R>". Si el último envío del servidor tiene más de 3 intervalos de antigüedad, añadir "⚠ sin datos recientes del servidor".
- Empates: más arriba quien llegó antes a ese valor (`taken_at` menor), y si no, por nombre.
- Muertes es un ranking "de más a menos" como los demás, solo que el título lo deja claro ("Más muertes").

### Comando opcional

`/ranking estadistica:<…> periodo:<total|semanal> [servidor]`: respuesta efímera con el top y la posición de quien lo ejecuta (por su `discordId`).

## 9. Configuración del mod

En `config/kb_discord_auth-common.toml`:

```toml
[general]
	serverName = "KB Survival"

[stats]
	enabled = true
	endpoint = "https://panel.tudominio.com/api/integrations/minecraft/stats"
	apiKey = "<la key que da el panel>"
	intervalMinutes = 5
	backfillOnStart = true
```

## 10. Probar el endpoint

```bash
curl -i -X POST "$PANEL_URL/api/integrations/minecraft/stats" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"version":1,"server":{"id":"0","name":"Prueba"},"sentAt":0,"players":[{"uuid":"f3d28cb0-7225-3cb1-baeb-2dadd2be89ae","name":"Tester","discordId":null,"online":false,"takenAt":1,"stats":{"playTimeSeconds":60,"deaths":1,"mobKills":2,"distanceCm":10000,"advancements":3}}]}'
```
