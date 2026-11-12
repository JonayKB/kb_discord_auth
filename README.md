# KB Discord Auth

Mod de Forge para **Minecraft 1.20.1** (Forge 47) que obliga a iniciar sesión con **Discord (OAuth2)** al entrar al servidor.

- **Login obligatorio.** Al entrar aparece una pantalla que no se puede cerrar, con el botón «Iniciar sesión con Discord». Hasta terminar, el jugador está congelado y no puede hablar, usar comandos, romper o colocar bloques, atacar, tirar o recoger objetos, ni recibir daño. Si no lo hace a tiempo, se le expulsa.
- **El login va primero.** Mientras el login está pendiente, las pantallas que abran otros mods al entrar (elegir dificultad, clase, bienvenida…) se aplazan y se abren en orden al terminar. Las pantallas de Minecraft y Forge no se tocan.
- **Solo la primera vez.** Tras vincular la cuenta, el servidor le da al cliente un token secreto. En las siguientes entradas se envía solo y no hace falta abrir el navegador. Sirve también en servidores no premium (`online-mode=false`): sin el token, quien use el nombre de otro tiene que iniciar sesión con el Discord de esa cuenta.
- **Roles en el chat y en el tab (solo visual).** Se toman de Discord con su nombre, color y orden. Se puede mostrar solo el más alto o todos, y excluir roles.
- **Color del rol sobre la cabeza.** El nombre encima del jugador toma el color de su rol, sin el prefijo.
- **Hover en el chat.** Al pasar el ratón por el nombre se ve el `@` de Discord.
- **Foto de perfil de Discord** redonda a la izquierda del nombre en el chat, y en el tab delante de la cabeza de la skin.
- **Rol obligatorio.** Sin alguno de los roles configurados no se entra.
- **Plaza reservada.** Un rol puede entrar aunque el servidor esté lleno.
- **Tab con estadísticas.** Arriba, el nombre del servidor y los jugadores conectados. Abajo, cada jugador ve las suyas: tiempo jugado, muertes, mobs matados, distancia y logros.
- **Ranking en el juego.** `/stats` abre una pantalla con botones: el top 10 de cada estadística (con todos los jugadores, también los desconectados) y tu puesto aunque no estés en el top, como `/ranking` en Discord, y una pestaña con todas tus estadísticas. `/stats <jugador>` muestra las de otro en el chat. Solo totales: el ranking semanal lo calcula el bot.
- **Login con Discord en webs del servidor.** Opcional: la misma aplicación de Discord sirve para iniciar sesión en, por ejemplo, la web de BlueMap. Lo usa [KB BlueMap Claims](https://github.com/JonayKB/kb_bluemap_claims) para enseñarte tu vida y la de tu party, y para seguir a tu personaje. Ver [Login en webs](#login-en-webs-bluemap).
- **Estadísticas para KB Discord Bot.** Opcional: el servidor envía esas estadísticas al bot para publicar un ranking (total y semanal) en un canal. El formato está en [`docs/stats-contract.md`](docs/stats-contract.md).

Va en **cliente y servidor**. Un cliente sin el mod no puede entrar.

Los bots de **KB Load Test** se dejan pasar: no hacen el login de Discord y no salen en las estadísticas, en `/stats` ni en el ranking del bot.

## Puesta en marcha

### 1. Aplicación de Discord

1. [Developer Portal](https://discord.com/developers/applications) → tu aplicación (puede ser la del bot) → **OAuth2**.
2. Copia el **Client ID** y el **Client Secret**.
3. En **Redirects** añade la URL pública del callback, p. ej. `http://play.tudominio.com:8085/callback`.
4. *(Recomendado)* Copia el **token del bot**. El bot tiene que estar en el servidor de Discord con el intent **Server Members**. Con él, los roles se leen en directo. Sin él, el mod pide a cada jugador el permiso `guilds.members.read` y guarda su refresh token para actualizar los roles.

### 2. Configuración del servidor

Arranca el servidor una vez con el mod, páralo y edita `config/kb_discord_auth-common.toml`.

> Edita este archivo **con el servidor apagado**. Forge recarga el TOML en caliente y, si lo lee a medio guardar, lo restablece a los valores por defecto: se perdería el Client Secret y se expulsaría a todo el mundo.

```toml
[general]
serverName = "KB Survival"   # sale en el tab ({server}) y en el ranking del bot

[discord]
clientId = "123456789012345678"
clientSecret = "..."
redirectUri = "http://play.tudominio.com:8085/callback"   # idéntica a la de Discord
guildId = "123456789012345678"
botToken = "..."                                           # opcional, recomendado

[http]
bindAddress = "0.0.0.0"   # 127.0.0.1 si usas nginx/Caddy delante
port = 8085               # ábrelo en el firewall si no hay proxy

[web]
enabled = false                                      # login en webs (ver «Login en webs»)
allowedOrigins = ["https://minecraft.tudominio.com"] # webs que pueden usarlo: esquema://host[:puerto], sin ruta
sessionDays = 30                                     # días que dura la sesión en la web

[access]
requireGuildMember = true
requiredRoles = ["111111111111111111"]       # vacío = no hace falta rol
bypassFullRoles = ["222222222222222222"]     # pueden entrar con el servidor lleno
roleSyncMinutes = 10
kickOnRoleLoss = true

[display]
mode = "HIGHEST"                  # HIGHEST = solo el rol más alto · ALL = todos, del más alto al más bajo
automaticRoles = true             # nombre, color y orden de Discord (necesita botToken)
autoPrefixFormat = "[{name}] "    # prefijo de cada rol, con el color del rol
includeUncoloredRoles = false     # los roles sin color (de avisos, etc.) no se ven
excludedRoles = ["333333333333333333"]   # nunca se ven (@everyone tampoco)
maxRoles = 0                      # en modo ALL, máximo de roles (0 = sin límite)
# Opcional: cambiar el aspecto de un rol concreto: "roleId|prefijo|colorDelNombre"
roles = [
  "111111111111111111|&4&l[Admin] |&c",
  "222222222222222222||#55FFFF",  # prefijo vacío: solo colorea el nombre
]
hoverFormat = "&9Discord: &f@{username}\\n&7Roles: &f{roles}"
showInNameTag = true              # nombre sobre la cabeza con el color del rol (sin prefijo)
showInTab = true
avatarsInChat = true              # foto de Discord a la izquierda del nombre en el chat
avatarsInTab = true               # foto de Discord en el tab, delante de la cabeza de la skin

[tab]
enabled = true
header = [                        # una línea por elemento; "" = línea en blanco
  "",
  "&b&l✦ {server} &b&l✦",
  "&7Jugadores conectados: &a{online}&8/&7{max}",
  "",
]
footer = [                        # cada jugador ve aquí sus estadísticas
  "",
  "&8&m                                        ",
  "&e⌛ &7Tiempo jugado  &f{playtime}",
  "&c☠ &7Muertes  &f{deaths}",
  "&a⚔ &7Mobs matados  &f{mobKills}",
  "&b➹ &7Distancia  &f{distance}",
  "&d★ &7Logros  &f{advancements}",
  "&8&m                                        ",
  "",
]
updateSeconds = 5

[stats]                           # envío al bot (ver docs/stats-contract.md)
enabled = false
endpoint = "https://panel.tudominio.com/api/integrations/minecraft/stats"
apiKey = "..."                    # la da el panel del bot
intervalMinutes = 5               # jugadores conectados; además, siempre al salir
backfillOnStart = true            # al arrancar, manda también las de quien haya entrado alguna vez
```

El nombre toma el color del rol más alto que se muestre y tenga color, como en Discord. Los roles de Discord se vuelven a leer al arrancar, cada `roleSyncMinutes` y con `/discordauth resync`, así que si cambias el nombre o el color de un rol en Discord se actualiza solo.

Sin `botToken` (o con `automaticRoles = false`) solo se ven los roles de la lista `roles`, en el orden de la lista.

Cada cliente descarga las fotos de perfil directamente de `cdn.discordapp.com`. Si alguien no tiene foto, o no carga, se ve el avatar por defecto de Discord. La foto se actualiza con cada sincronización de roles. En los servidores no premium (`online-mode=false`) Minecraft no dibuja las cabezas en el tab, así que ahí solo se ve la foto de Discord.

La distancia suma andar, correr, nadar, escalar, élitros y montar, pero no el vuelo en creativo ni las caídas. Los logros cuentan solo los que salen en la pantalla de logros (no las recetas). Al bot no se envían los jugadores que aún no han iniciado sesión con Discord.

Los mensajes de expulsión también están en el TOML (`[messages]`). El archivo es `COMMON` y no se envía a los clientes, así que los secretos se quedan en el servidor.

Para servir el callback por **HTTPS**, pon un proxy delante (`proxy_pass http://127.0.0.1:8085;`) y usa `redirectUri = "https://auth.tudominio.com/callback"`.

### 3. Cliente

Mete el mismo `.jar` en el modpack. No hace falta configurar nada.

## Comandos

Para todos:

| Comando | Qué hace |
|---|---|
| `/stats` | Abre la pantalla de rankings y de tus estadísticas |
| `/stats <jugador>` | Muestra en el chat las estadísticas de ese jugador y su puesto en cada ranking |

Para administradores (permiso 3):

| Comando | Qué hace |
|---|---|
| `/discordauth info <jugador>` | Muestra el Discord vinculado, si está en el servidor de Discord y sus roles |
| `/discordauth unlink <jugador>` | Borra el vínculo y lo expulsa si está conectado |
| `/discordauth resync` | Vuelve a leer los roles del servidor de Discord y los de todos los conectados |

## Cómo funciona

```
entra ─► pantalla de login ─► el cliente envía su token guardado
          ├─ token válido ──► lee los roles ─► ¿tiene acceso? ─► juega
          └─ sin token ─────► navegador ─► Discord ─► /callback ─► vincula y da un token nuevo ─► ¿tiene acceso? ─► juega
```

- Los vínculos están en `config/kb_discord_auth/links.json` (servidor). Se guarda un hash del token, no el token. Edítalo solo con el servidor apagado, porque al cerrarse lo sobrescribe.
- Los tokens del cliente están en `config/kb_discord_auth/client_tokens.json`, uno por servidor y cuenta. Si se borra, el jugador vuelve a iniciar sesión con Discord.
- Una cuenta de Minecraft ya vinculada no se puede vincular a otro Discord (usa `unlink`). Por defecto, un Discord admite una sola cuenta de Minecraft (`maxAccountsPerDiscord`).
- Si Discord no responde, se usan los últimos roles guardados.

### Login en webs (BlueMap)

Con `[web] enabled = true`, el servidor HTTP del callback también hace el login de Discord para las webs de `allowedOrigins`. Usa la misma `redirectUri`, así que en Discord no hay que añadir nada. Solo pide el permiso `identify`.

```
web ─► GET /web/login?return=URL ─► Discord ─► /callback ─► URL?kbauth=CÓDIGO   (un solo uso, 2 min)
web ─► POST /web/session (CÓDIGO) ─► token de sesión (lo guarda el navegador)
web ─► GET /web/me · GET /web/api/<nombre> · POST /web/logout   (Authorization: Bearer <token>)
```

- La web solo ve las cuentas de Minecraft vinculadas a ese Discord que ahora mismo pueden entrar al servidor (mismas reglas de roles que al entrar). Quien nunca ha entrado al servidor no tiene ninguna.
- Las sesiones están en `config/kb_discord_auth/web_sessions.json`, guardadas como hash. Sobreviven a los reinicios.
- Solo se devuelve a URLs de `allowedOrigins`, y solo esas webs reciben las cabeceras CORS.
- **HTTPS:** si la web va por `https://`, el login también tiene que ir por `https://` (el navegador bloquea mezclar). Con nginx delante, p. ej. `auth.tudominio.com` → `127.0.0.1:8085` y `minecraft.tudominio.com` → BlueMap, no hace falta nada más.

**Para otros mods.** Un mod puede responder en `/web/api/<nombre>` enviando un mensaje IMC a `kb_discord_auth` con el método `web_api` y este contenido:

```java
BiFunction<Map<UUID, String>, Map<String, String>, String> handler = (accounts, query) -> "{...}";
InterModComms.sendTo("kb_discord_auth", "web_api", () -> Map.entry("nombre", handler));
```

`accounts` son las cuentas (UUID → nombre) del Discord que ha iniciado sesión, de la vinculada más recientemente a la más antigua, y `query` son los parámetros de la URL. Devuelve el JSON, o `null` para un 404. Se llama desde un hilo HTTP, no desde el del servidor. No hace falta depender del código de este mod.

### Límites conocidos

- **Plaza reservada en modo offline.** Cuando el servidor está lleno, la decisión se toma antes de recibir el token, solo por el nombre y con los roles guardados. Alguien que use el nombre de un jugador con plaza reservada ocuparía una plaza hasta que se le expulse por no iniciar sesión (`loginTimeoutSeconds`).
- **Sin bot**, los refresh tokens de Discord se guardan en claro en `links.json`. Con `botToken` no se guarda ninguno.
- En mundos de un jugador o LAN está desactivado (`enableInSingleplayer`).
- La foto de perfil solo se dibuja en el chat y en el tab. En otros sitios donde Minecraft muestra el nombre, como la pantalla de muerte, queda un hueco pequeño delante del nombre, y en la consola del servidor, un espacio.

## Desarrollo

```bash
./gradlew build        # build/libs/kb_discord_auth-<versión>.jar
./gradlew runServer    # carpeta run/
./gradlew runClient    # carpeta run-client/ (separada para no pisar la config del servidor)
```

Para probar sin Discord real, arranca el servidor con `-Dkb_discord_auth.apiBase=http://127.0.0.1:PUERTO/api/v10` apuntando a una API falsa (y `-Dkb_discord_auth.authorizeUrl=http://127.0.0.1:PUERTO/oauth2/authorize` para el login en webs), y el cliente con `-Dkb_discord_auth.cdnBase=http://127.0.0.1:PUERTO` para las fotos.

## Licencia

MIT
