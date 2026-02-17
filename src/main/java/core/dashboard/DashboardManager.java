package core.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.config.ConfigManager;
import core.anticheat.AntiCheatManager;
import core.claims.ClaimManager;
import core.clans.ClanManager;
import core.economy.EconomyManager;
import core.logging.LoggingManager;
import core.moderation.ModerationManager;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class DashboardManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().create();
    private static HttpServer httpServer;
    private static MinecraftServer server;
    private static long startedAtMs;
    private static final Pattern ACTOR_EVENT_PATTERN = Pattern.compile("\\] [A-Z_]+: ([^\\-\\[]+?)(?: - .*)?$");
    private static final Pattern ACTOR_COMMAND_PATTERN = Pattern.compile("\\] ([^\\]]+?) executed:");
    private static final Pattern ACTOR_CHAT_PATTERN = Pattern.compile("\\] ([^:]+): ");
    private static final List<String> CHANNEL_ORDER = List.of("command", "private", "chat", "game", "event");
    private static final SecureRandom SESSION_RNG = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private DashboardManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> Safe.run("DashboardManager.start", () -> start(s)));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("DashboardManager.stop", DashboardManager::stop));
    }

    private static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        startedAtMs = System.currentTimeMillis();
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardEnabled) {
            LOGGER.info("Dashboard disabled in config.");
            return;
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(cfg.logging.dashboardHost, cfg.logging.dashboardPort), 0);
            httpServer.createContext("/", DashboardManager::handleIndex);
            httpServer.createContext("/login", DashboardManager::handleLogin);
            httpServer.createContext("/logout", DashboardManager::handleLogout);
            httpServer.createContext("/api/status", DashboardManager::handleStatus);
            httpServer.createContext("/api/players", DashboardManager::handlePlayers);
            httpServer.createContext("/api/events", DashboardManager::handleEvents);
            httpServer.createContext("/api/events.csv", DashboardManager::handleEventsCsv);
            httpServer.createContext("/api/summary", DashboardManager::handleSummary);
            httpServer.createContext("/api/activity", DashboardManager::handleActivity);
            httpServer.createContext("/api/metrics", DashboardManager::handleMetrics);
            httpServer.createContext("/api/timeseries", DashboardManager::handleTimeSeries);
            httpServer.createContext("/api/top-commands", DashboardManager::handleTopCommands);
            httpServer.createContext("/api/worlds", DashboardManager::handleWorlds);
            httpServer.createContext("/api/economy/top", DashboardManager::handleEconomyTop);
            httpServer.createContext("/api/economy/player", DashboardManager::handleEconomyPlayer);
            httpServer.createContext("/api/claims/list", DashboardManager::handleClaimsList);
            httpServer.createContext("/api/claims/unclaim", DashboardManager::handleClaimsUnclaim);
            httpServer.createContext("/api/claims/permission", DashboardManager::handleClaimsPermission);
            httpServer.createContext("/api/clans/list", DashboardManager::handleClansList);
            httpServer.createContext("/api/clans/action", DashboardManager::handleClansAction);
            httpServer.createContext("/api/mod/history", DashboardManager::handleModHistory);
            httpServer.createContext("/api/mod/note", DashboardManager::handleModNote);
            httpServer.createContext("/api/mod/warn", DashboardManager::handleModWarn);
            httpServer.createContext("/api/player/detail", DashboardManager::handlePlayerDetail);
            httpServer.createContext("/api/player/inventory", DashboardManager::handlePlayerInventory);
            httpServer.createContext("/api/player/enderchest", DashboardManager::handlePlayerEnderChest);
            httpServer.createContext("/api/player/action", DashboardManager::handlePlayerAction);
            httpServer.createContext("/api/admin/overview", DashboardManager::handleAdminOverview);
            httpServer.createContext("/api/control/command", DashboardManager::handleControlCommand);
            httpServer.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "core-dashboard-http");
                t.setDaemon(true);
                return t;
            }));
            httpServer.start();
            LOGGER.info("Dashboard started at http://{}:{}/", cfg.logging.dashboardHost, cfg.logging.dashboardPort);
        } catch (IOException e) {
            LOGGER.error("Failed to start dashboard HTTP server", e);
        }
    }

    private static void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("Dashboard stopped.");
        }
        server = null;
    }

    private static void handleIndex(HttpExchange ex) throws IOException {
        if (!authorizedForHtml(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        String html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Core Dashboard</title>
            <style>
            :root{--bg:#0b0f16;--panel:#141a24;--panel2:#10151f;--text:#e5edf7;--muted:#8da2ba;--line:#263243;--ok:#27d17f;--err:#ff5f6d;--chip:#1d2635}
            a{color:#7cd7ff}
            *{box-sizing:border-box} body{margin:0;font-family:Segoe UI,system-ui,sans-serif;background:radial-gradient(1200px 600px at 20% -10%,#1b2638 0%,var(--bg) 55%);color:var(--text)}
            .wrap{max-width:1200px;margin:24px auto;padding:0 16px}
            .top{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}
            .title{font-size:24px;font-weight:700}
            .subtitle{color:var(--muted);font-size:13px}
            .grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px}
            .kpi{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:10px;padding:12px}
            .kpi .label{font-size:12px;color:var(--muted)} .kpi .value{font-size:21px;font-weight:700;margin-top:4px}
            .ok{color:var(--ok)} .err{color:var(--err)}
            .card{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:10px;padding:12px;margin-bottom:12px}
            .row{display:grid;grid-template-columns:1fr 1fr;gap:12px}
            table{width:100%;border-collapse:collapse} th,td{padding:8px;border-bottom:1px solid var(--line);text-align:left;font-size:13px}
            th{color:var(--muted);font-weight:600}
            .toolbar{display:flex;gap:8px;align-items:center;margin-bottom:10px}
            select,input{background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 8px}
            .mono{font-family:Consolas,Monaco,monospace}
            button{background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 10px;cursor:pointer}
            .controls{display:grid;grid-template-columns:2fr 1fr 1fr auto;gap:8px}
            .tabs{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}
            .tab{background:#0e141e;border:1px solid var(--line);border-radius:999px;padding:6px 10px;cursor:pointer}
            .tab.active{border-color:#2dd4ff}
            .hidden{display:none}
            .hint{font-size:12px;color:var(--muted)}
            @media(max-width:960px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.row{grid-template-columns:1fr}}
            </style></head><body>
            <div class='wrap'>
              <div class='top'>
                <div>
                  <div class='title'>Core Mod Dashboard</div>
                  <div class='subtitle'>Live server telemetry</div>
                </div>
                <div class='subtitle' id='lastUpdate'>updated: -</div>
              </div>
              <div class='grid'>
                <div class='kpi'><div class='label'>Server</div><div class='value' id='kOnline'>-</div></div>
                <div class='kpi'><div class='label'>Players</div><div class='value' id='kPlayers'>-</div></div>
                <div class='kpi'><div class='label'>Version</div><div class='value' id='kVersion'>-</div></div>
                <div class='kpi'><div class='label'>MOTD</div><div class='value' id='kMotd'>-</div></div>
                <div class='kpi'><div class='label'>Uptime</div><div class='value' id='kUptime'>-</div></div>
                <div class='kpi'><div class='label'>Events (last 200)</div><div class='value' id='kEventRate'>-</div></div>
                <div class='kpi'><div class='label'>Memory</div><div class='value' id='kMemory'>-</div></div>
                <div class='kpi'><div class='label'>Threads</div><div class='value' id='kThreads'>-</div></div>
              </div>
              <div class='tabs'>
                <button class='tab active' data-tab='tab-overview'>Overview</button>
                <button class='tab' data-tab='tab-admin'>Admin</button>
                <button class='tab' data-tab='tab-events'>Events</button>
              </div>

              <div id='tab-overview'>
              <div class='row'>
                <div class='card'>
                  <b>Players Online</b>
                  <table id='playersTable'>
                    <thead><tr><th>Name</th><th>World</th><th>Pos</th></tr></thead>
                    <tbody><tr><td colspan='3'>loading...</td></tr></tbody>
                  </table>
                </div>
                <div class='card'>
                  <b>Event Channels</b>
                  <table id='channelsTable'>
                    <thead><tr><th>Channel</th><th>Count</th></tr></thead>
                    <tbody><tr><td colspan='2'>loading...</td></tr></tbody>
                  </table>
                  <br/>
                  <b>Top Activity (last 200)</b>
                  <table id='activityTable'>
                    <thead><tr><th>Player</th><th>Actions</th></tr></thead>
                    <tbody><tr><td colspan='2'>loading...</td></tr></tbody>
                  </table>
                  <br/>
                  <b>Top Commands</b>
                  <table id='commandsTable'>
                    <thead><tr><th>Command</th><th>Count</th></tr></thead>
                    <tbody><tr><td colspan='2'>loading...</td></tr></tbody>
                  </table>
                  <br/>
                  <b>World Distribution</b>
                  <table id='worldsTable'>
                    <thead><tr><th>World</th><th>Players</th></tr></thead>
                    <tbody><tr><td colspan='2'>loading...</td></tr></tbody>
                  </table>
                  <br/>
                  <b>Charts</b>
                  <div class='hint' style='margin:6px 0 8px 0'>Last 60 minutes (per minute)</div>
                  <canvas id='chart' width='520' height='180' style='width:100%;height:180px;background:#0e141e;border:1px solid var(--line);border-radius:10px'></canvas>
                  <br/>
                  <b>Raw Status</b>
                  <pre id='statusRaw' class='mono'>{}</pre>
                </div>
              </div>
              </div>

              <div id='tab-admin' class='hidden'>
              <div class='card'>
                <b>Controls</b>
                <div class='hint' style='margin:6px 0 10px 0'>Requires dashboard token and enabled server-side control in config.</div>
                <div class='controls'>
                  <input id='cmdInput' placeholder='Enter server command (without /)'/>
                  <button id='runCmdBtn'>Run Command</button>
                  <button id='saveBtn'>Save All</button>
                  <button id='listBtn'>List Players</button>
                </div>
                <div class='controls' style='margin-top:8px;grid-template-columns:2fr 1fr auto auto'>
                  <input id='annInput' placeholder='Announcement text'/>
                  <select id='annMode'>
                    <option value='chat'>chat</option>
                    <option value='actionbar'>actionbar</option>
                    <option value='both'>both</option>
                  </select>
                  <button id='annBtn'>Send Announcement</button>
                  <button id='reloadAnnBtn'>Reload Announcements</button>
                </div>
                <div id='controlResult' class='hint' style='margin-top:8px'>Ready</div>
              </div>
              <div class='card'>
                <b>Player Admin</b>
                <div class='controls' style='grid-template-columns:2fr auto auto auto auto'>
                  <input id='playerInput' placeholder='Player name'/>
                  <button id='viewDetailBtn'>Details</button>
                  <button id='viewInvBtn'>Inventory</button>
                  <button id='viewEChestBtn'>Ender Chest</button>
                  <button id='tpSpawnBtn'>TP to Spawn</button>
                </div>
                <div class='controls' style='margin-top:8px;grid-template-columns:1fr 1fr 1fr auto'>
                  <select id='playerAction'>
                    <option value='freeze'>freeze</option>
                    <option value='unfreeze'>unfreeze</option>
                    <option value='reset_violations'>reset_violations</option>
                    <option value='mute'>mute</option>
                    <option value='unmute'>unmute</option>
                    <option value='heal'>heal</option>
                    <option value='feed'>feed</option>
                    <option value='kill'>kill</option>
                    <option value='kick'>kick</option>
                    <option value='gamemode'>gamemode</option>
                    <option value='set_balance'>set_balance</option>
                  </select>
                  <input id='playerActionValue' placeholder='value (reason/mode/amount)'/>
                  <button id='runPlayerActionBtn'>Run Player Action</button>
                  <button id='refreshOverviewBtn'>Refresh Overview</button>
                </div>
                <pre id='playerData' class='mono' style='max-height:260px;overflow:auto;margin-top:8px'>{}</pre>
              </div>
              <div class='card'>
                <b>Admin Overview</b>
                <pre id='adminOverview' class='mono' style='max-height:320px;overflow:auto'>{}</pre>
              </div>
              <div class='card'>
                <b>Economy</b>
                <div class='controls' style='grid-template-columns:1fr 1fr auto'>
                  <input id='ecoLookup' placeholder='uuid or online player name'/>
                  <button id='ecoLookupBtn'>Lookup</button>
                  <button id='ecoTopBtn'>Top Balances</button>
                </div>
                <pre id='ecoData' class='mono' style='max-height:320px;overflow:auto;margin-top:8px'>{}</pre>
              </div>
              <div class='card'>
                <b>Claims</b>
                <div class='controls' style='grid-template-columns:1fr auto auto'>
                  <input id='claimChunk' placeholder='chunk key: x,z'/>
                  <button id='claimsRefreshBtn'>Refresh Claims</button>
                  <button id='claimsUnclaimBtn'>Force Unclaim</button>
                </div>
                <div class='controls' style='margin-top:8px;grid-template-columns:1fr 1fr auto'>
                  <input id='claimPermChunk' placeholder='chunk key: x,z'/>
                  <select id='claimPerm'>
                    <option value='BUILD'>BUILD</option>
                    <option value='BREAK'>BREAK</option>
                    <option value='CONTAINER'>CONTAINER</option>
                    <option value='REDSTONE'>REDSTONE</option>
                    <option value='PVP'>PVP</option>
                    <option value='EXPLOSIONS'>EXPLOSIONS</option>
                  </select>
                  <button id='claimsPermToggleBtn'>Toggle Permission</button>
                </div>
                <pre id='claimsData' class='mono' style='max-height:320px;overflow:auto;margin-top:8px'>{}</pre>
              </div>
              <div class='card'>
                <b>Clans</b>
                <div class='controls' style='grid-template-columns:1fr auto auto'>
                  <input id='clanName' placeholder='clan name'/>
                  <button id='clansRefreshBtn'>Refresh Clans</button>
                  <button id='clanDisbandBtn'>Disband</button>
                </div>
                <div class='controls' style='margin-top:8px;grid-template-columns:1fr 1fr auto auto'>
                  <input id='clanSetBank' placeholder='bank amount'/>
                  <input id='clanSetLevel' placeholder='level'/>
                  <button id='clanSetBankBtn'>Set Bank</button>
                  <button id='clanSetLevelBtn'>Set Level</button>
                </div>
                <pre id='clansData' class='mono' style='max-height:320px;overflow:auto;margin-top:8px'>{}</pre>
              </div>
              <div class='card'>
                <b>Moderation Notes/Warns</b>
                <div class='controls' style='grid-template-columns:1fr auto auto'>
                  <input id='modTarget' placeholder='online player name or uuid'/>
                  <button id='modLookupBtn'>Lookup</button>
                  <button id='modWarnBtn'>Warn</button>
                </div>
                <div class='controls' style='margin-top:8px;grid-template-columns:1fr auto'>
                  <input id='modText' placeholder='note/warn text'/>
                  <button id='modNoteBtn'>Add Note</button>
                </div>
                <pre id='modData' class='mono' style='max-height:320px;overflow:auto;margin-top:8px'>{}</pre>
              </div>
              </div>

              <div id='tab-events' class='hidden'>
              <div class='card'>
                <div class='toolbar'>
                  <b style='margin-right:auto'>Recent Events</b>
                  <input id='search' placeholder='search text'/>
                  <select id='channel'>
                    <option value='all'>all channels</option>
                    <option value='event'>event</option>
                    <option value='chat'>chat</option>
                    <option value='command'>command</option>
                    <option value='game'>game</option>
                    <option value='private'>private</option>
                  </select>
                  <input id='limit' type='number' min='10' max='500' step='10' value='80'/>
                  <button id='exportJsonBtn'>Export JSON</button>
                  <button id='exportCsvBtn'>Export CSV</button>
                  <button id='pauseBtn'>Pause</button>
                </div>
                <table id='eventsTable'>
                  <thead><tr><th>Time</th><th>Channel</th><th>Message</th></tr></thead>
                  <tbody><tr><td colspan='3'>loading...</td></tr></tbody>
                </table>
              </div>
              </div>
            </div>
            <script>
            // tabs
            document.querySelectorAll('.tab').forEach(btn=>{
              btn.addEventListener('click', ()=>{
                document.querySelectorAll('.tab').forEach(b=>b.classList.remove('active'));
                btn.classList.add('active');
                const id = btn.getAttribute('data-tab');
                document.querySelectorAll('#tab-overview,#tab-admin,#tab-events').forEach(p=>p.classList.add('hidden'));
                const panel = document.getElementById(id);
                if(panel) panel.classList.remove('hidden');
              });
            });
            let paused = false;
            const token = new URLSearchParams(window.location.search).get('token');
            function withToken(path){
              if(!token) return path;
              return path + (path.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
            }
            function esc(v){return String(v??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
            function drawChart(series){
              const canvas = document.getElementById('chart');
              if(!canvas) return;
              const ctx = canvas.getContext('2d');
              const w = canvas.width, h = canvas.height;
              ctx.clearRect(0,0,w,h);
              ctx.fillStyle = '#0e141e';
              ctx.fillRect(0,0,w,h);
              const labels = series.labels || [];
              const lines = series.series || {};
              const keys = Object.keys(lines);
              if(labels.length === 0 || keys.length === 0) return;
              let max = 1;
              keys.forEach(k => (lines[k]||[]).forEach(v => { if(v>max) max=v; }));
              // grid
              ctx.strokeStyle = '#263243';
              ctx.lineWidth = 1;
              for(let i=0;i<=4;i++){
                const y = 10 + (h-20)*(i/4);
                ctx.beginPath(); ctx.moveTo(10,y); ctx.lineTo(w-10,y); ctx.stroke();
              }
              const colors = {event:'#2dd4ff',chat:'#a78bfa',command:'#9ae6b4',game:'#fbbf24',private:'#fb7185'};
              keys.forEach(k=>{
                const arr = lines[k]||[];
                ctx.strokeStyle = colors[k] || '#e5edf7';
                ctx.lineWidth = 2;
                ctx.beginPath();
                for(let i=0;i<arr.length;i++){
                  const x = 10 + (w-20) * (i/(arr.length-1));
                  const y = 10 + (h-20) * (1 - (arr[i]/max));
                  if(i===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
                }
                ctx.stroke();
              });
              // legend
              ctx.font = '12px Segoe UI';
              let lx = 12, ly = 16;
              keys.forEach(k=>{
                ctx.fillStyle = colors[k] || '#e5edf7';
                ctx.fillRect(lx, ly-10, 10, 10);
                ctx.fillStyle = '#e5edf7';
                ctx.fillText(k, lx+14, ly);
                lx += 70;
              });
            }
            function fmtDate(v){
              if(!v) return '-';
              try{
                const d = typeof v === 'number' ? new Date(v) : new Date(v);
                return d.toLocaleString();
              }catch(_){return String(v);}
            }
            async function load(){
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              if(paused) return;
              const [s,p,e,m,x,a,c,w,t]=await Promise.all([
                fetch(withToken('/api/status')),
                fetch(withToken('/api/players')),
                fetch(withToken('/api/events?limit=' + limit)),
                fetch(withToken('/api/summary')),
                fetch(withToken('/api/metrics')),
                fetch(withToken('/api/activity?limit=200')),
                fetch(withToken('/api/top-commands?limit=300')),
                fetch(withToken('/api/worlds')),
                fetch(withToken('/api/timeseries?minutes=60'))
              ]);
              const status = await s.json();
              const players = await p.json();
              const events = await e.json();
              const summary = await m.json();
              const metrics = await x.json();
              const activity = await a.json();
              const commands = await c.json();
              const worlds = await w.json();
              const timeseries = await t.json();

              if(status.error){throw new Error('status: ' + status.error);}
              if(players.error){throw new Error('players: ' + players.error);}
              if(events.error){throw new Error('events: ' + events.error);}
              if(summary.error){throw new Error('summary: ' + summary.error);}
              if(metrics.error){throw new Error('metrics: ' + metrics.error);}
              if(activity.error){throw new Error('activity: ' + activity.error);}
              if(commands.error){throw new Error('commands: ' + commands.error);}
              if(worlds.error){throw new Error('worlds: ' + worlds.error);}
              if(timeseries.error){throw new Error('timeseries: ' + timeseries.error);}

              document.getElementById('kOnline').textContent = status.online ? 'ONLINE' : 'OFFLINE';
              document.getElementById('kOnline').className = 'value ' + (status.online ? 'ok' : 'err');
              document.getElementById('kPlayers').textContent = (status.players ?? 0) + ' / ' + (status.maxPlayers ?? 0);
              document.getElementById('kVersion').textContent = status.version ?? '-';
              document.getElementById('kMotd').textContent = status.motd ?? '-';
              document.getElementById('kUptime').textContent = summary.uptime ?? '-';
              document.getElementById('kEventRate').textContent = (summary.totalEventsLast200 ?? 0).toString();
              document.getElementById('kMemory').textContent = (metrics.heapUsedMB ?? 0) + ' / ' + (metrics.heapMaxMB ?? 0) + ' MB';
              document.getElementById('kThreads').textContent = String(metrics.threadCount ?? '-');
              document.getElementById('lastUpdate').textContent = 'updated: ' + fmtDate(status.timestamp);
              document.getElementById('statusRaw').textContent = JSON.stringify(status, null, 2);

              const pBody = document.querySelector('#playersTable tbody');
              const plist = (Array.isArray(players.players) ? players.players : []).sort((a,b)=>String(a.name||'').localeCompare(String(b.name||'')));
              if(plist.length === 0){
                pBody.innerHTML = "<tr><td colspan='3'>No players online</td></tr>";
              } else {
                pBody.innerHTML = plist.map(x =>
                  "<tr><td>" + esc(x.name) + "</td><td>" + esc(x.world) + "</td><td>" +
                  [Math.round(x.x||0),Math.round(x.y||0),Math.round(x.z||0)].join(', ') + "</td></tr>"
                ).join('');
              }

              const channel = document.getElementById('channel').value;
              const search = (document.getElementById('search').value || '').toLowerCase();
              let ev = Array.isArray(events.events) ? events.events : [];
              ev = ev.sort((a,b)=>(Number(b.timestamp)||0)-(Number(a.timestamp)||0));
              if(channel !== 'all'){ ev = ev.filter(x => (x.channel || '') === channel); }
              if(search){ ev = ev.filter(x => String(x.message || '').toLowerCase().includes(search)); }
              const eBody = document.querySelector('#eventsTable tbody');
              if(ev.length === 0){
                eBody.innerHTML = "<tr><td colspan='3'>No events</td></tr>";
              } else {
                eBody.innerHTML = ev.map(x =>
                  "<tr><td>" + esc(fmtDate(x.timestamp)) + "</td><td><span style='background:var(--chip);padding:2px 6px;border-radius:999px'>" + esc(x.channel) + "</span></td><td class='mono'>" + esc(x.message) + "</td></tr>"
                ).join('');
              }

              const chBody = document.querySelector('#channelsTable tbody');
              const rows = Array.isArray(summary.channelRows)
                ? summary.channelRows.map(x => [x.channel, x.count])
                : Object.entries(summary.channelCounts || {}).sort((a,b)=>b[1]-a[1]);
              if(rows.length === 0){
                chBody.innerHTML = "<tr><td colspan='2'>No channel data</td></tr>";
              } else {
                chBody.innerHTML = rows.map(r => "<tr><td>" + esc(r[0]) + "</td><td>" + esc(r[1]) + "</td></tr>").join('');
              }

              const aBody = document.querySelector('#activityTable tbody');
              const actors = Array.isArray(activity.players) ? activity.players : [];
              if(actors.length === 0){
                aBody.innerHTML = "<tr><td colspan='2'>No activity yet</td></tr>";
              } else {
                aBody.innerHTML = actors.map(r => "<tr><td>" + esc(r.name) + "</td><td>" + esc(r.count) + "</td></tr>").join('');
              }

              const cBody = document.querySelector('#commandsTable tbody');
              const cmdRows = Array.isArray(commands.commands) ? commands.commands : [];
              if(cmdRows.length === 0){
                cBody.innerHTML = "<tr><td colspan='2'>No command data</td></tr>";
              } else {
                cBody.innerHTML = cmdRows.map(r => "<tr><td>/" + esc(r.command) + "</td><td>" + esc(r.count) + "</td></tr>").join('');
              }

              const wBody = document.querySelector('#worldsTable tbody');
              const worldRows = Array.isArray(worlds.worlds) ? worlds.worlds : [];
              if(worldRows.length === 0){
                wBody.innerHTML = "<tr><td colspan='2'>No players online</td></tr>";
              } else {
                wBody.innerHTML = worldRows.map(r => "<tr><td>" + esc(r.world) + "</td><td>" + esc(r.players) + "</td></tr>").join('');
              }
              drawChart(timeseries);
              const ov = await fetch(withToken('/api/admin/overview')).then(r=>r.json());
              document.getElementById('adminOverview').textContent = JSON.stringify(ov, null, 2);
            }
            document.getElementById('channel').addEventListener('change', load);
            document.getElementById('limit').addEventListener('change', load);
            document.getElementById('search').addEventListener('input', load);
            document.getElementById('exportJsonBtn').addEventListener('click', () => {
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              const channel = document.getElementById('channel').value;
              const search = encodeURIComponent(document.getElementById('search').value || '');
              window.open(withToken('/api/events?limit=' + limit + '&channel=' + encodeURIComponent(channel) + '&search=' + search), '_blank');
            });
            document.getElementById('exportCsvBtn').addEventListener('click', () => {
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              const channel = document.getElementById('channel').value;
              const search = encodeURIComponent(document.getElementById('search').value || '');
              window.open(withToken('/api/events.csv?limit=' + limit + '&channel=' + encodeURIComponent(channel) + '&search=' + search), '_blank');
            });
            document.getElementById('pauseBtn').addEventListener('click', () => {
              paused = !paused;
              document.getElementById('pauseBtn').textContent = paused ? 'Resume' : 'Pause';
              if(!paused) load();
            });
            async function postCommand(command){
              const res = await fetch(withToken('/api/control/command'),{
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({command})
              });
              const data = await res.json();
              const box = document.getElementById('controlResult');
              if(data.error){
                box.textContent = 'Error: ' + data.error;
                return;
              }
              box.textContent = 'OK: ' + (data.command || command) + ' -> result=' + (data.result ?? '?');
            }
            document.getElementById('runCmdBtn').addEventListener('click', async () => {
              const cmd = (document.getElementById('cmdInput').value || '').trim();
              if(!cmd) return;
              await postCommand(cmd);
              load();
            });
            document.getElementById('saveBtn').addEventListener('click', async () => { await postCommand('save-all'); load(); });
            document.getElementById('listBtn').addEventListener('click', async () => { await postCommand('list'); load(); });
            document.getElementById('annBtn').addEventListener('click', async () => {
              const msg = (document.getElementById('annInput').value || '').trim();
              const mode = (document.getElementById('annMode').value || 'chat').trim();
              if(!msg) return;
              await postCommand('announce ' + mode + ' ' + msg);
              load();
            });
            document.getElementById('reloadAnnBtn').addEventListener('click', async () => { await postCommand('announce reload'); load(); });
            async function loadPlayer(path){
              const name = (document.getElementById('playerInput').value || '').trim();
              if(!name) return;
              const res = await fetch(withToken(path + '?name=' + encodeURIComponent(name)));
              const data = await res.json();
              document.getElementById('playerData').textContent = JSON.stringify(data, null, 2);
            }
            document.getElementById('viewDetailBtn').addEventListener('click', () => loadPlayer('/api/player/detail'));
            document.getElementById('viewInvBtn').addEventListener('click', () => loadPlayer('/api/player/inventory'));
            document.getElementById('viewEChestBtn').addEventListener('click', () => loadPlayer('/api/player/enderchest'));
            document.getElementById('tpSpawnBtn').addEventListener('click', async () => {
              const name = (document.getElementById('playerInput').value || '').trim();
              if(!name) return;
              await postCommand('execute as ' + name + ' run spawn');
              load();
            });
            document.getElementById('runPlayerActionBtn').addEventListener('click', async () => {
              const name = (document.getElementById('playerInput').value || '').trim();
              const action = (document.getElementById('playerAction').value || '').trim();
              const value = (document.getElementById('playerActionValue').value || '').trim();
              if(!name || !action) return;
              const res = await fetch(withToken('/api/player/action'),{
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({name, action, value})
              });
              const data = await res.json();
              document.getElementById('playerData').textContent = JSON.stringify(data, null, 2);
              load();
            });
            document.getElementById('refreshOverviewBtn').addEventListener('click', load);

            document.getElementById('ecoLookupBtn').addEventListener('click', async () => {
              const raw = (document.getElementById('ecoLookup').value || '').trim();
              if(!raw) return;
              let url = '/api/economy/player?';
              if(raw.includes('-') && raw.length > 20) url += 'uuid=' + encodeURIComponent(raw);
              else url += 'name=' + encodeURIComponent(raw);
              const data = await fetch(withToken(url)).then(r=>r.json());
              document.getElementById('ecoData').textContent = JSON.stringify(data, null, 2);
            });
            document.getElementById('ecoTopBtn').addEventListener('click', async () => {
              const data = await fetch(withToken('/api/economy/top?limit=25&currency=COINS')).then(r=>r.json());
              document.getElementById('ecoData').textContent = JSON.stringify(data, null, 2);
            });

            async function refreshClaims(){
              const data = await fetch(withToken('/api/claims/list')).then(r=>r.json());
              document.getElementById('claimsData').textContent = JSON.stringify(data, null, 2);
            }
            document.getElementById('claimsRefreshBtn').addEventListener('click', refreshClaims);
            document.getElementById('claimsUnclaimBtn').addEventListener('click', async () => {
              const chunk = (document.getElementById('claimChunk').value || '').trim();
              if(!chunk) return;
              const data = await fetch(withToken('/api/claims/unclaim'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({chunk})
              }).then(r=>r.json());
              document.getElementById('claimsData').textContent = JSON.stringify(data, null, 2);
              refreshClaims();
            });
            document.getElementById('claimsPermToggleBtn').addEventListener('click', async () => {
              const chunk = (document.getElementById('claimPermChunk').value || '').trim();
              const permission = (document.getElementById('claimPerm').value || '').trim();
              if(!chunk || !permission) return;
              // naive toggle: read current snapshot and flip if found
              const snap = await fetch(withToken('/api/claims/list')).then(r=>r.json());
              let current = false;
              if(snap && Array.isArray(snap.claims)){
                const found = snap.claims.find(x=>x.chunk===chunk);
                if(found && found.permissions && found.permissions[permission] !== undefined){
                  current = !!found.permissions[permission];
                }
              }
              const allowed = !current;
              const data = await fetch(withToken('/api/claims/permission'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({chunk, permission, allowed})
              }).then(r=>r.json());
              document.getElementById('claimsData').textContent = JSON.stringify(data, null, 2);
              refreshClaims();
            });

            async function refreshClans(){
              const data = await fetch(withToken('/api/clans/list')).then(r=>r.json());
              document.getElementById('clansData').textContent = JSON.stringify(data, null, 2);
            }
            document.getElementById('clansRefreshBtn').addEventListener('click', refreshClans);
            document.getElementById('clanDisbandBtn').addEventListener('click', async () => {
              const clan = (document.getElementById('clanName').value || '').trim();
              if(!clan) return;
              const data = await fetch(withToken('/api/clans/action'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({action:'disband', clan, value:''})
              }).then(r=>r.json());
              document.getElementById('clansData').textContent = JSON.stringify(data, null, 2);
              refreshClans();
            });
            document.getElementById('clanSetBankBtn').addEventListener('click', async () => {
              const clan = (document.getElementById('clanName').value || '').trim();
              const value = (document.getElementById('clanSetBank').value || '').trim();
              if(!clan || !value) return;
              const data = await fetch(withToken('/api/clans/action'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({action:'set_bank', clan, value})
              }).then(r=>r.json());
              document.getElementById('clansData').textContent = JSON.stringify(data, null, 2);
              refreshClans();
            });
            document.getElementById('clanSetLevelBtn').addEventListener('click', async () => {
              const clan = (document.getElementById('clanName').value || '').trim();
              const value = (document.getElementById('clanSetLevel').value || '').trim();
              if(!clan || !value) return;
              const data = await fetch(withToken('/api/clans/action'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({action:'set_level', clan, value})
              }).then(r=>r.json());
              document.getElementById('clansData').textContent = JSON.stringify(data, null, 2);
              refreshClans();
            });

            async function modLookup(){
              const raw = (document.getElementById('modTarget').value || '').trim();
              if(!raw) return;
              let url = '/api/mod/history?';
              if(raw.includes('-') && raw.length > 20) url += 'uuid=' + encodeURIComponent(raw);
              else url += 'name=' + encodeURIComponent(raw);
              const data = await fetch(withToken(url)).then(r=>r.json());
              document.getElementById('modData').textContent = JSON.stringify(data, null, 2);
            }
            document.getElementById('modLookupBtn').addEventListener('click', modLookup);
            document.getElementById('modNoteBtn').addEventListener('click', async () => {
              const raw = (document.getElementById('modTarget').value || '').trim();
              const note = (document.getElementById('modText').value || '').trim();
              if(!raw || !note) return;
              const payload = raw.includes('-') && raw.length>20 ? {uuid:raw, note} : {name:raw, note};
              const data = await fetch(withToken('/api/mod/note'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify(payload)
              }).then(r=>r.json());
              document.getElementById('modData').textContent = JSON.stringify(data, null, 2);
              modLookup();
            });
            document.getElementById('modWarnBtn').addEventListener('click', async () => {
              const name = (document.getElementById('modTarget').value || '').trim();
              const reason = (document.getElementById('modText').value || '').trim();
              if(!name || !reason) return;
              const data = await fetch(withToken('/api/mod/warn'),{
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({name, reason})
              }).then(r=>r.json());
              document.getElementById('modData').textContent = JSON.stringify(data, null, 2);
              modLookup();
            });
            load(); setInterval(load, 3000);
            </script></body></html>
            """;
        send(ex, 200, "text/html; charset=utf-8", html);
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardLoginEnabled) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }

        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String html = """
                <!doctype html>
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Dashboard Login</title>
                <style>
                :root{--bg:#0b0f16;--panel:#141a24;--text:#e5edf7;--muted:#8da2ba;--line:#263243;--err:#ff5f6d}
                *{box-sizing:border-box} body{margin:0;font-family:Segoe UI,system-ui,sans-serif;background:radial-gradient(1200px 600px at 20% -10%,#1b2638 0%,var(--bg) 55%);color:var(--text)}
                .wrap{max-width:420px;margin:64px auto;padding:0 16px}
                .card{background:linear-gradient(180deg,var(--panel),#10151f);border:1px solid var(--line);border-radius:14px;padding:16px}
                .title{font-size:20px;font-weight:700;margin-bottom:6px}
                .sub{color:var(--muted);font-size:13px;margin-bottom:12px}
                input{width:100%;background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:10px;padding:10px 12px;font-size:14px}
                button{margin-top:10px;width:100%;background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:10px;padding:10px 12px;font-size:14px;cursor:pointer}
                .err{margin-top:10px;color:var(--err);font-size:13px}
                </style></head>
                <body><div class="wrap">
                  <div class="card">
                    <div class="title">Admin Login</div>
                    <div class="sub">CraftZone Nexus Dashboard</div>
                    <form method="post" action="/login">
                      <input name="password" type="password" placeholder="Password" autofocus />
                      <button type="submit">Login</button>
                    </form>
                    <div class="err" id="err"></div>
                  </div>
                </div>
                <script>
                  const p=new URLSearchParams(window.location.search);
                  const e=p.get('e'); if(e){document.getElementById('err').textContent=e;}
                </script>
                </body></html>
                """;
            send(ex, 200, "text/html; charset=utf-8", html);
            return;
        }

        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String password = parseFormValue(body, "password");
        if (!verifyDashboardPassword(password)) {
            redirect(ex, "/login?e=Invalid%20password");
            return;
        }
        String sid = newSessionId();
        long ttlMs = Math.max(5, cfg.logging.dashboardSessionMinutes) * 60_000L;
        SESSIONS.put(sid, new Session(System.currentTimeMillis() + ttlMs));
        setSessionCookie(ex, sid, cfg.logging.dashboardCookieSecure);
        redirect(ex, "/");
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        var cfg = ConfigManager.getConfig();
        String sid = getCookie(ex, "core_session");
        if (sid != null) SESSIONS.remove(sid);
        if (cfg != null && cfg.logging != null) {
            clearSessionCookie(ex, cfg.logging.dashboardCookieSecure);
        }
        redirect(ex, "/login");
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("online", server != null);
        out.put("timestamp", Instant.now().toString());
        if (server != null) {
            out.put("motd", server.getServerMotd());
            out.put("players", server.getCurrentPlayerCount());
            out.put("maxPlayers", server.getMaxPlayerCount());
            out.put("version", server.getVersion());
        }
        sendJson(ex, out);
    }

    private static void handlePlayers(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new HashMap<>();
        if (server == null) {
            out.put("players", List.of());
            sendJson(ex, out);
            return;
        }
        List<Map<String, Object>> players = server.getPlayerManager().getPlayerList().stream().map(p -> {
            Map<String, Object> row = new HashMap<>();
            row.put("name", p.getName().getString());
            row.put("uuid", p.getUuidAsString());
            row.put("world", p.getEntityWorld().getRegistryKey().getValue().toString());
            row.put("x", p.getX());
            row.put("y", p.getY());
            row.put("z", p.getZ());
            return row;
        }).toList();
        out.put("players", players);
        sendJson(ex, out);
    }

    private static void handleEvents(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var query = query(ex);
        int limit = parseInt(query.get("limit"), 50);
        String channel = query.getOrDefault("channel", "all");
        String search = query.getOrDefault("search", "");
        var entries = filterEvents(limit, channel, search).stream().map(e -> {
            Map<String, Object> row = new HashMap<>();
            row.put("timestamp", e.timestamp);
            row.put("channel", e.channel);
            row.put("message", e.message);
            return row;
        }).toList();
        Map<String, Object> out = new HashMap<>();
        out.put("events", entries);
        sendJson(ex, out);
    }

    private static void handleEventsCsv(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method_not_allowed");
            return;
        }
        var query = query(ex);
        int limit = parseInt(query.get("limit"), 100);
        String channel = query.getOrDefault("channel", "all");
        String search = query.getOrDefault("search", "");
        var entries = filterEvents(limit, channel, search);

        StringBuilder sb = new StringBuilder("timestamp,channel,message\n");
        for (var e : entries) {
            sb.append(e.timestamp).append(',')
              .append(csv(e.channel)).append(',')
              .append(csv(e.message)).append('\n');
        }
        send(ex, 200, "text/csv; charset=utf-8", sb.toString());
    }

    private static void handleSummary(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }

        Map<String, Object> out = new HashMap<>();
        out.put("uptime", formatUptime(System.currentTimeMillis() - startedAtMs));
        Map<String, Integer> counts = LoggingManager.getRecentChannelCounts(200);
        out.put("channelCounts", counts);
        var rows = counts.entrySet().stream()
            .sorted((a, b) -> {
                int ia = channelIndex(a.getKey());
                int ib = channelIndex(b.getKey());
                if (ia != ib) return Integer.compare(ia, ib);
                int byCount = Integer.compare(b.getValue(), a.getValue());
                if (byCount != 0) return byCount;
                return a.getKey().compareToIgnoreCase(b.getKey());
            })
            .map(e -> {
                Map<String, Object> row = new HashMap<>();
                row.put("channel", e.getKey());
                row.put("count", e.getValue());
                return row;
            })
            .toList();
        out.put("channelRows", rows);
        out.put("totalEventsLast200", counts.values().stream().mapToInt(Integer::intValue).sum());
        sendJson(ex, out);
    }

    private static void handleActivity(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }

        var query = query(ex);
        int limit = parseInt(query.get("limit"), 200);
        Map<String, Integer> counts = new HashMap<>();
        for (var e : LoggingManager.getRecentLogs(Math.max(1, Math.min(limit, 500)))) {
            String actor = parseActor(e.message);
            if (actor == null || actor.isBlank()) continue;
            counts.merge(actor, 1, Integer::sum);
        }

        var top = counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(15)
            .map(entry -> {
                Map<String, Object> row = new HashMap<>();
                row.put("name", entry.getKey());
                row.put("count", entry.getValue());
                return row;
            })
            .toList();

        Map<String, Object> out = new HashMap<>();
        out.put("players", top);
        sendJson(ex, out);
    }

    private static void handleMetrics(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        Map<String, Object> out = new HashMap<>();
        out.put("heapUsedMB", used / (1024 * 1024));
        out.put("heapMaxMB", max / (1024 * 1024));
        out.put("threadCount", Thread.getAllStackTraces().size());
        out.put("onlinePlayers", server == null ? 0 : server.getCurrentPlayerCount());
        out.put("maxPlayers", server == null ? 0 : server.getMaxPlayerCount());
        sendJson(ex, out);
    }

    private static void handleTimeSeries(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var q = query(ex);
        int minutes = parseInt(q.get("minutes"), 60);
        minutes = Math.max(5, Math.min(minutes, 6 * 60));
        long now = System.currentTimeMillis();
        long since = now - (minutes * 60_000L);
        int buckets = minutes;

        String[] channels = new String[]{"event", "chat", "command", "game", "private"};
        Map<String, int[]> series = new LinkedHashMap<>();
        for (String c : channels) series.put(c, new int[buckets]);

        for (var e : LoggingManager.getRecentLogsSince(since, 5000)) {
            int idx = (int) ((e.timestamp - since) / 60_000L);
            if (idx < 0 || idx >= buckets) continue;
            int[] arr = series.get(e.channel == null ? "" : e.channel.toLowerCase(Locale.ROOT));
            if (arr != null) arr[idx]++;
        }

        List<String> labels = new java.util.ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            labels.add(String.valueOf(i - (buckets - 1))); // relative minutes
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minutes", minutes);
        out.put("labels", labels);
        Map<String, List<Integer>> outSeries = new LinkedHashMap<>();
        for (var entry : series.entrySet()) {
            int[] arr = entry.getValue();
            List<Integer> vals = new java.util.ArrayList<>(arr.length);
            for (int v : arr) vals.add(v);
            outSeries.put(entry.getKey(), vals);
        }
        out.put("series", outSeries);
        sendJson(ex, out);
    }

    private static void handleTopCommands(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        int limit = parseInt(query(ex).get("limit"), 200);
        Map<String, Integer> counts = new HashMap<>();
        for (var e : LoggingManager.getRecentLogs(Math.max(10, Math.min(limit, 500)))) {
            if (!"command".equalsIgnoreCase(e.channel)) continue;
            String cmd = extractCommandName(e.message);
            if (cmd == null || cmd.isBlank()) continue;
            counts.merge(cmd, 1, Integer::sum);
        }
        List<Map<String, Object>> rows = counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(12)
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("command", entry.getKey());
                row.put("count", entry.getValue());
                return row;
            })
            .toList();
        Map<String, Object> out = new HashMap<>();
        out.put("commands", rows);
        sendJson(ex, out);
    }

    private static void handleWorlds(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        if (server != null) {
            for (var p : server.getPlayerManager().getPlayerList()) {
                String world = p.getEntityWorld().getRegistryKey().getValue().toString();
                counts.merge(world, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rows = counts.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed())
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("world", entry.getKey());
                row.put("players", entry.getValue());
                return row;
            })
            .toList();
        Map<String, Object> out = new HashMap<>();
        out.put("worlds", rows);
        sendJson(ex, out);
    }

    private static void handleEconomyTop(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var query = query(ex);
        int limit = parseInt(query.get("limit"), 25);
        limit = Math.max(1, Math.min(limit, 100));
        String currencyRaw = query.getOrDefault("currency", "COINS");
        EconomyManager.Currency currency = EconomyManager.Currency.parseOrNull(currencyRaw);
        if (currency == null) currency = EconomyManager.Currency.COINS;
        final EconomyManager.Currency ccy = currency;

        Map<UUID, java.math.BigDecimal> snap = EconomyManager.snapshotBalances(ccy);
        List<Map<String, Object>> rows = snap.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(limit)
            .<Map<String, Object>>map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", entry.getKey().toString());
                row.put("balance", EconomyManager.formatCurrency(entry.getValue(), ccy));
                return row;
            }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", ccy.name());
        out.put("rows", rows);
        sendJson(ex, out);
    }

    private static void handleEconomyPlayer(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var query = query(ex);
        String uuidRaw = query.get("uuid");
        String nameRaw = query.get("name");
        UUID uuid = null;
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try { uuid = UUID.fromString(uuidRaw.trim()); } catch (Exception ignored) {}
        }
        if (uuid == null && server != null && nameRaw != null && !nameRaw.isBlank()) {
            var p = server.getPlayerManager().getPlayer(nameRaw.trim());
            if (p != null) uuid = p.getUuid();
        }
        if (uuid == null) {
            send(ex, 400, "application/json", "{\"error\":\"missing_uuid_or_online_name\"}");
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uuid", uuid.toString());
        for (EconomyManager.Currency c : EconomyManager.Currency.values()) {
            out.put("balance_" + c.name().toLowerCase(), EconomyManager.formatCurrency(EconomyManager.getBalance(uuid, c), c));
        }
        out.put("transactions", EconomyManager.getRecentTransactions(uuid, 25).stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", t.timestamp);
            row.put("type", t.type == null ? null : t.type.name());
            row.put("amount", t.amount == null ? null : t.amount.toPlainString());
            row.put("description", t.description);
            row.put("from", t.fromPlayer == null ? null : t.fromPlayer.toString());
            row.put("to", t.toPlayer == null ? null : t.toPlayer.toString());
            return row;
        }).toList());
        sendJson(ex, out);
    }

    private static void handleClaimsList(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ConfigManager.getConfig() != null && ConfigManager.getConfig().claim != null && ConfigManager.getConfig().claim.enableClaims);
        List<Map<String, Object>> rows = ClaimManager.getAllClaimsSnapshot().entrySet().stream()
            .limit(3000)
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunk", e.getKey());
                var c = e.getValue();
                row.put("type", c.type == null ? null : c.type.name());
                row.put("owner", c.ownerId == null ? null : c.ownerId.toString());
                row.put("clan", c.clanName);
                row.put("overdue", c.isOverdue);
                row.put("claimedAt", c.claimedAt);
                row.put("lastPaid", c.lastPaid);
                row.put("permissions", c.permissions == null ? Map.of() : c.permissions);
                return row;
            })
            .toList();
        out.put("claims", rows);
        out.put("count", rows.size());
        sendJson(ex, out);
    }

    private static void handleClaimsUnclaim(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try { req = GSON.fromJson(body, Map.class); } catch (Exception ignored) { req = null; }
        String chunkKey = req == null ? null : String.valueOf(req.get("chunk"));
        if (chunkKey == null || chunkKey.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_chunk\"}");
            return;
        }
        boolean ok = ClaimManager.adminUnclaimByKey(chunkKey.trim());
        sendJson(ex, Map.of("ok", ok, "chunk", chunkKey.trim()));
    }

    private static void handleClaimsPermission(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try { req = GSON.fromJson(body, Map.class); } catch (Exception ignored) { req = null; }
        String chunkKey = req == null ? null : String.valueOf(req.get("chunk"));
        String permRaw = req == null ? null : String.valueOf(req.get("permission"));
        boolean allowed = req != null && Boolean.parseBoolean(String.valueOf(req.get("allowed")));
        if (chunkKey == null || chunkKey.isBlank() || permRaw == null || permRaw.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_fields\"}");
            return;
        }
        ClaimManager.ClaimPermission perm;
        try {
            perm = ClaimManager.ClaimPermission.valueOf(permRaw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            send(ex, 400, "application/json", "{\"error\":\"invalid_permission\"}");
            return;
        }
        boolean ok = ClaimManager.adminSetClaimPermission(chunkKey.trim(), perm, allowed);
        sendJson(ex, Map.of("ok", ok, "chunk", chunkKey.trim(), "permission", perm.name(), "allowed", allowed));
    }

    private static void handleClansList(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = ClanManager.getAllClans().values().stream()
            .sorted((a, b) -> Integer.compare(b.members.size(), a.members.size()))
            .limit(500)
            .map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", c.name);
                row.put("tag", c.tag);
                row.put("level", c.level);
                row.put("members", c.members == null ? 0 : c.members.size());
                row.put("bank", c.bankBalance);
                row.put("leader", c.leader == null ? null : c.leader.toString());
                row.put("createdAt", c.createdAt);
                return row;
            }).toList();
        out.put("clans", rows);
        out.put("count", rows.size());
        sendJson(ex, out);
    }

    private static void handleClansAction(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try { req = GSON.fromJson(body, Map.class); } catch (Exception ignored) { req = null; }
        String action = req == null ? null : String.valueOf(req.get("action"));
        String clan = req == null ? null : String.valueOf(req.get("clan"));
        String value = req == null ? "" : String.valueOf(req.get("value"));
        if (action == null || action.isBlank() || clan == null || clan.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_fields\"}");
            return;
        }
        boolean ok;
        switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "set_bank" -> {
                try { ok = ClanManager.adminSetClanBank(clan.trim(), Double.parseDouble(value.trim())); }
                catch (Exception e) { send(ex, 400, "application/json", "{\"error\":\"invalid_amount\"}"); return; }
            }
            case "set_level" -> {
                try { ok = ClanManager.adminSetClanLevel(clan.trim(), Integer.parseInt(value.trim())); }
                catch (Exception e) { send(ex, 400, "application/json", "{\"error\":\"invalid_level\"}"); return; }
            }
            case "disband" -> ok = ClanManager.adminDisbandClan(clan.trim());
            default -> { send(ex, 400, "application/json", "{\"error\":\"unknown_action\"}"); return; }
        }
        sendJson(ex, Map.of("ok", ok, "action", action.trim(), "clan", clan.trim(), "value", value));
    }

    private static void handleModHistory(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String uuidRaw = query(ex).get("uuid");
        String nameRaw = query(ex).get("name");
        UUID uuid = null;
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try { uuid = UUID.fromString(uuidRaw.trim()); } catch (Exception ignored) {}
        }
        if (uuid == null && server != null && nameRaw != null && !nameRaw.isBlank()) {
            var p = server.getPlayerManager().getPlayer(nameRaw.trim());
            if (p != null) uuid = p.getUuid();
        }
        if (uuid == null) {
            send(ex, 400, "application/json", "{\"error\":\"missing_uuid_or_online_name\"}");
            return;
        }
        var rec = ModerationManager.get(uuid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uuid", uuid.toString());
        out.put("notes", rec == null ? List.of() : rec.notes);
        out.put("warns", rec == null ? List.of() : rec.warns);
        sendJson(ex, out);
    }

    private static void handleModNote(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try { req = GSON.fromJson(body, Map.class); } catch (Exception ignored) { req = null; }
        String note = req == null ? null : String.valueOf(req.get("note"));
        String name = req == null ? null : String.valueOf(req.get("name"));
        String uuidRaw = req == null ? null : String.valueOf(req.get("uuid"));
        UUID uuid = null;
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try { uuid = UUID.fromString(uuidRaw.trim()); } catch (Exception ignored) {}
        }
        if (uuid == null) {
            ServerPlayerEntity p = findPlayerByName(name);
            if (p != null) uuid = p.getUuid();
        }
        if (uuid == null || note == null || note.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_target_or_note\"}");
            return;
        }
        ModerationManager.addNote(uuid, null, note);
        sendJson(ex, Map.of("ok", true, "uuid", uuid.toString()));
    }

    private static void handleModWarn(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try { req = GSON.fromJson(body, Map.class); } catch (Exception ignored) { req = null; }
        String reason = req == null ? null : String.valueOf(req.get("reason"));
        String name = req == null ? null : String.valueOf(req.get("name"));
        ServerPlayerEntity p = findPlayerByName(name);
        if (p == null || reason == null || reason.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_online_player_or_reason\"}");
            return;
        }
        ModerationManager.addWarn(p.getUuid(), null, reason);
        sendJson(ex, Map.of("ok", true, "uuid", p.getUuidAsString()));
    }

    // Placeholder for Phase 3: backups are implemented via command allowlist (e.g. save-all).
    // Full file-level backup/restore is intentionally not implemented here to avoid writing world backups from a mod.
    // On hosts like GPortal, use built-in backup tools or a dedicated backup mod/plugin.

    private static void handleControlCommand(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        if (server == null) {
            send(ex, 503, "application/json", "{\"error\":\"server_unavailable\"}");
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try {
            req = GSON.fromJson(body, Map.class);
        } catch (Exception ignored) {
            req = null;
        }
        String command = req == null ? null : String.valueOf(req.get("command"));
        command = command == null ? "" : command.trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_command\"}");
            return;
        }
        if (!isAllowedControlCommand(command, cfg.logging.dashboardAllowedCommandPrefixes)) {
            send(ex, 403, "application/json", "{\"error\":\"command_not_allowed\"}");
            return;
        }

        AtomicInteger result = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        String cmd = command;
        server.execute(() -> {
            try {
                server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd);
                result.set(1);
            } catch (Exception ignored) {
                result.set(0);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("command", command);
        out.put("result", result.get());
        sendJson(ex, out);
    }

    private static void handlePlayerDetail(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (server == null) {
            send(ex, 503, "application/json", "{\"error\":\"server_unavailable\"}");
            return;
        }
        String name = query(ex).get("name");
        ServerPlayerEntity p = findPlayerByName(name);
        if (p == null) {
            send(ex, 404, "application/json", "{\"error\":\"player_not_found\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName().getString());
        out.put("uuid", p.getUuidAsString());
        out.put("world", p.getEntityWorld().getRegistryKey().getValue().toString());
        out.put("x", p.getX());
        out.put("y", p.getY());
        out.put("z", p.getZ());
        out.put("health", p.getHealth());
        out.put("food", p.getHungerManager().getFoodLevel());
        out.put("gamemode", p.interactionManager.getGameMode().asString());
        sendJson(ex, out);
    }

    private static void handlePlayerInventory(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowSensitivePlayerData) {
            send(ex, 403, "application/json", "{\"error\":\"sensitive_data_disabled\"}");
            return;
        }
        if (server == null) {
            send(ex, 503, "application/json", "{\"error\":\"server_unavailable\"}");
            return;
        }
        String name = query(ex).get("name");
        ServerPlayerEntity p = findPlayerByName(name);
        if (p == null) {
            send(ex, 404, "application/json", "{\"error\":\"player_not_found\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName().getString());
        out.put("inventory", summarizeInventory(p.getInventory()));
        sendJson(ex, out);
    }

    private static void handlePlayerEnderChest(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowSensitivePlayerData) {
            send(ex, 403, "application/json", "{\"error\":\"sensitive_data_disabled\"}");
            return;
        }
        if (server == null) {
            send(ex, 503, "application/json", "{\"error\":\"server_unavailable\"}");
            return;
        }
        String name = query(ex).get("name");
        ServerPlayerEntity p = findPlayerByName(name);
        if (p == null) {
            send(ex, 404, "application/json", "{\"error\":\"player_not_found\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName().getString());
        out.put("enderChest", summarizeInventory(p.getEnderChestInventory()));
        sendJson(ex, out);
    }

    private static void handlePlayerAction(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (server == null) {
            send(ex, 503, "application/json", "{\"error\":\"server_unavailable\"}");
            return;
        }
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardAllowControl) {
            send(ex, 403, "application/json", "{\"error\":\"control_disabled\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> req;
        try {
            req = GSON.fromJson(body, Map.class);
        } catch (Exception ignored) {
            req = null;
        }
        String name = req == null ? null : String.valueOf(req.get("name"));
        String action = req == null ? null : String.valueOf(req.get("action"));
        String value = req == null ? "" : String.valueOf(req.get("value"));
        if (name == null || name.isBlank() || action == null || action.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing_name_or_action\"}");
            return;
        }
        ServerPlayerEntity p = findPlayerByName(name);
        if (p == null) {
            send(ex, 404, "application/json", "{\"error\":\"player_not_found\"}");
            return;
        }

        String cmd = null;
        switch (action.trim().toLowerCase()) {
            case "freeze" -> cmd = "ac freeze " + p.getName().getString();
            case "unfreeze" -> cmd = "ac unfreeze " + p.getName().getString();
            case "reset_violations" -> cmd = "ac reset " + p.getName().getString();
            case "mute" -> {
                long minutes = 0;
                String reason = value == null ? "" : value.trim();
                // Accept formats: "10 reason..." or just "reason" (permanent)
                try {
                    String[] parts = reason.split("\\s+", 2);
                    if (parts.length > 0 && parts[0].matches("\\d+")) {
                        minutes = Long.parseLong(parts[0]);
                        reason = parts.length > 1 ? parts[1] : "";
                    }
                } catch (Exception ignored) {
                }
                long durationMs = minutes <= 0 ? 0 : minutes * 60_000L;
                ModerationManager.mute(p.getUuid(), null, durationMs, reason);
            }
            case "unmute" -> ModerationManager.unmute(p.getUuid());
            case "heal" -> {
                p.setHealth(p.getMaxHealth());
                p.setAir(p.getMaxAir());
            }
            case "feed" -> p.getHungerManager().setFoodLevel(20);
            case "kill" -> cmd = "kill " + p.getName().getString();
            case "kick" -> cmd = "kick " + p.getName().getString() + " " + (value == null || value.isBlank() ? "Kicked by dashboard" : value);
            case "gamemode" -> {
                String mode = value == null || value.isBlank() ? "survival" : value.trim().toLowerCase();
                if (!List.of("survival", "creative", "adventure", "spectator").contains(mode)) {
                    send(ex, 400, "application/json", "{\"error\":\"invalid_gamemode\"}");
                    return;
                }
                cmd = "gamemode " + mode + " " + p.getName().getString();
            }
            case "set_balance" -> {
                try {
                    double amount = Double.parseDouble(value == null ? "" : value.trim());
                    cmd = "eco set " + p.getName().getString() + " " + amount;
                } catch (Exception e) {
                    send(ex, 400, "application/json", "{\"error\":\"invalid_amount\"}");
                    return;
                }
            }
            default -> {
                send(ex, 400, "application/json", "{\"error\":\"unknown_action\"}");
                return;
            }
        }
        if (cmd != null && !isAllowedControlCommand(cmd, cfg.logging.dashboardAllowedCommandPrefixes)) {
            send(ex, 403, "application/json", "{\"error\":\"command_not_allowed\"}");
            return;
        }
        if (cmd != null) {
            String finalCmd = cmd;
            server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), finalCmd));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("player", p.getName().getString());
        out.put("action", action);
        out.put("value", value);
        sendJson(ex, out);
    }

    private static void handleAdminOverview(HttpExchange ex) throws IOException {
        if (!authorized(ex, true)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (server == null) {
            out.put("online", false);
            sendJson(ex, out);
            return;
        }
        out.put("online", true);
        out.put("players", server.getCurrentPlayerCount());
        out.put("maxPlayers", server.getMaxPlayerCount());
        out.put("whitelistEnabled", server.getPlayerManager().isWhitelistEnabled());
        var cfg = ConfigManager.getConfig();
        boolean includeLists = cfg != null && cfg.logging != null && cfg.logging.dashboardAllowSecurityLists;
        if (includeLists) {
            out.put("whitelistedPlayers", server.getPlayerManager().getWhitelistedNames());
            out.put("bannedPlayers", server.getPlayerManager().getUserBanList().getNames());
            out.put("ops", server.getPlayerManager().getOpList().getNames());
        } else {
            out.put("whitelistedPlayers", List.of());
            out.put("bannedPlayers", List.of());
            out.put("ops", List.of());
        }
        out.put("securityListsIncluded", includeLists);

        List<Map<String, Object>> economyTop = EconomyManager.snapshotBalances(EconomyManager.Currency.COINS).entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(10)
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", entry.getKey().toString());
                row.put("balance", EconomyManager.formatCurrency(entry.getValue()));
                return row;
            }).toList();
        out.put("economyTop", economyTop);

        out.put("clanCount", ClanManager.getAllClans().size());
        List<Map<String, Object>> clans = ClanManager.getAllClans().values().stream()
            .sorted((a, b) -> Integer.compare(b.members.size(), a.members.size()))
            .limit(10)
            .map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", c.name);
                row.put("tag", c.tag);
                row.put("members", c.members.size());
                row.put("bank", c.bankBalance);
                return row;
            }).toList();
        out.put("clans", clans);

        List<Map<String, Object>> claimRows = server.getPlayerManager().getPlayerList().stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", p.getName().getString());
            row.put("claims", ClaimManager.getPlayerClaimCount(p.getUuid()));
            return row;
        }).toList();
        out.put("claimsByOnlinePlayer", claimRows);

        List<Map<String, Object>> antiCheat = server.getPlayerManager().getPlayerList().stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", p.getName().getString());
            var data = AntiCheatManager.getPlayerData(p.getUuid());
            row.put("violationLevel", data == null ? 0.0 : data.violationLevel);
            row.put("frozen", AntiCheatManager.isFrozen(p.getUuid()));
            return row;
        }).toList();
        out.put("anticheat", antiCheat);
        sendJson(ex, out);
    }

    private static String formatUptime(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        return h + "h " + m + "m " + s + "s";
    }

    private static ServerPlayerEntity findPlayerByName(String name) {
        if (server == null || name == null || name.isBlank()) return null;
        return server.getPlayerManager().getPlayer(name.trim());
    }

    private static List<Map<String, Object>> summarizeInventory(net.minecraft.inventory.Inventory inv) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", i);
            row.put("itemId", stack.getItem().toString());
            row.put("count", stack.getCount());
            row.put("name", stack.getName().getString());
            out.add(row);
        }
        return out;
    }

    private static String extractCommandName(String message) {
        if (message == null) return null;
        int idx = message.indexOf(" executed: ");
        if (idx < 0) return null;
        String raw = message.substring(idx + " executed: ".length()).trim();
        if (raw.startsWith("/")) raw = raw.substring(1);
        if (raw.isBlank()) return null;
        return raw.split("\\s+")[0].toLowerCase();
    }

    private static List<LoggingManager.RecentLogEntry> filterEvents(int limit, String channel, String search) {
        String wantedChannel = channel == null ? "all" : channel.trim().toLowerCase();
        String needle = search == null ? "" : search.trim().toLowerCase();
        return LoggingManager.getRecentLogs(limit).stream()
            .filter(e -> "all".equals(wantedChannel) || e.channel.equalsIgnoreCase(wantedChannel))
            .filter(e -> needle.isEmpty() || e.message.toLowerCase().contains(needle))
            .toList();
    }

    private static String parseActor(String message) {
        if (message == null || message.isBlank()) return null;

        Matcher m = ACTOR_COMMAND_PATTERN.matcher(message);
        if (m.find()) return m.group(1).trim();

        m = ACTOR_EVENT_PATTERN.matcher(message);
        if (m.find()) return m.group(1).trim();

        m = ACTOR_CHAT_PATTERN.matcher(message);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    private static int channelIndex(String channel) {
        if (channel == null) return Integer.MAX_VALUE;
        int idx = CHANNEL_ORDER.indexOf(channel.toLowerCase());
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean isAllowedControlCommand(String command, String[] allowedPrefixes) {
        if (command == null || command.isBlank()) return false;
        if (allowedPrefixes == null || allowedPrefixes.length == 0) return true;
        String first = command.trim().split("\\s+")[0].toLowerCase();
        return Arrays.stream(allowedPrefixes)
            .filter(x -> x != null && !x.isBlank())
            .map(x -> x.trim().toLowerCase())
            .anyMatch(first::equals);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getQuery();
        if (q == null || q.isBlank()) return out;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i <= 0) continue;
            String k = p.substring(0, i);
            String v = p.substring(i + 1);
            try {
                out.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                out.put(k, v);
            }
        }
        return out;
    }

    private static String csv(String s) {
        if (s == null) return "";
        String esc = s.replace("\"", "\"\"");
        return "\"" + esc + "\"";
    }

    private static boolean authorized(HttpExchange ex) throws IOException {
        return authorized(ex, false);
    }

    private static boolean authorizedForHtml(HttpExchange ex) throws IOException {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null) return true;
        if (!ipAllowed(ex, cfg, true)) return false;

        // If login flow is enabled, browser UI requires a valid session.
        if (cfg.logging.dashboardLoginEnabled) {
            if (isValidSession(ex, cfg)) return true;
            redirect(ex, "/login");
            return false;
        }

        // Fallback to token-based auth for HTML when login is disabled.
        return authorized(ex, false);
    }

    private static boolean authorized(HttpExchange ex, boolean requireAdmin) throws IOException {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null) {
            return true;
        }

        if (!ipAllowed(ex, cfg, false)) return false;

        // Session cookie is treated as admin access for API calls.
        if (cfg.logging.dashboardLoginEnabled && isValidSession(ex, cfg)) {
            return true;
        }

        String legacyAdmin = trimToNull(cfg.logging.dashboardToken);
        String readTokenCfg = trimToNull(cfg.logging.dashboardReadToken);
        String adminTokenCfg = trimToNull(cfg.logging.dashboardAdminToken);
        if (adminTokenCfg == null && legacyAdmin != null) adminTokenCfg = legacyAdmin;

        if (readTokenCfg == null && adminTokenCfg == null) {
            return true;
        }

        String headerToken = trimToNull(ex.getRequestHeaders().getFirst("X-Core-Token"));
        String queryToken = trimToNull(getQueryToken(ex));
        String authHeader = trimToNull(ex.getRequestHeaders().getFirst("Authorization"));
        String bearerToken = authHeader != null && authHeader.toLowerCase().startsWith("bearer ")
            ? trimToNull(authHeader.substring(7))
            : null;

        String provided = headerToken != null ? headerToken : (queryToken != null ? queryToken : bearerToken);
        if (provided == null) {
            send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return false;
        }

        boolean isAdmin = adminTokenCfg != null && adminTokenCfg.equals(provided);
        boolean isRead = readTokenCfg != null && readTokenCfg.equals(provided);

        if (requireAdmin) {
            if (isAdmin) return true;
            send(ex, 403, "application/json", "{\"error\":\"admin_required\"}");
            return false;
        }

        if (isAdmin || isRead) return true;
        send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
        return false;
    }

    private static boolean ipAllowed(HttpExchange ex, ConfigManager.Config cfg, boolean html) throws IOException {
        if (cfg == null || cfg.logging == null) return true;
        if (cfg.logging.dashboardAllowedIps == null || cfg.logging.dashboardAllowedIps.length == 0) return true;

        String ip = ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
            ? null
            : ex.getRemoteAddress().getAddress().getHostAddress();

        boolean allowed = false;
        if (ip != null) {
            for (String allowedIp : cfg.logging.dashboardAllowedIps) {
                if (allowedIp != null && !allowedIp.isBlank() && ip.equals(allowedIp.trim())) {
                    allowed = true;
                    break;
                }
            }
        }
        if (allowed) return true;

        if (html) {
            send(ex, 403, "text/plain; charset=utf-8", "ip_not_allowed");
        } else {
            send(ex, 403, "application/json", "{\"error\":\"ip_not_allowed\"}");
        }
        return false;
    }

    private static boolean isValidSession(HttpExchange ex, ConfigManager.Config cfg) {
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardLoginEnabled) return false;
        String sid = getCookie(ex, "core_session");
        if (sid == null) return false;
        Session session = SESSIONS.get(sid);
        if (session == null) return false;
        long now = System.currentTimeMillis();
        if (session.expiresAtMs <= now) {
            SESSIONS.remove(sid);
            return false;
        }
        return true;
    }

    private static String parseFormValue(String body, String key) {
        if (body == null || body.isBlank() || key == null || key.isBlank()) return "";
        String prefix = key + "=";
        for (String part : body.split("&")) {
            if (!part.startsWith(prefix)) continue;
            String raw = part.substring(prefix.length());
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {
                return raw.trim();
            }
        }
        return "";
    }

    private static boolean verifyDashboardPassword(String password) {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null) return false;
        String given = trimToNull(password);
        String hash = trimToNull(cfg.logging.dashboardAdminPasswordHash);
        if (given == null) return false;
        if (cfg.logging.dashboardBackupPasswordEnabled) {
            String backup = trimToNull(cfg.logging.dashboardBackupPassword);
            if (backup != null && backup.equals(given)) return true;
        }
        if (hash == null) return false;
        try {
            String[] parts = hash.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equalsIgnoreCase(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            PBEKeySpec spec = new PBEKeySpec(given.toCharArray(), salt, iterations, expected.length * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actual = skf.generateSecret(spec).getEncoded();
            return constantTimeEquals(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= (a[i] ^ b[i]);
        return r == 0;
    }

    private static String newSessionId() {
        byte[] token = new byte[32];
        SESSION_RNG.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static void setSessionCookie(HttpExchange ex, String sid, boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append("core_session=").append(sid).append("; Path=/; HttpOnly; SameSite=Strict");
        if (secure) sb.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    private static void clearSessionCookie(HttpExchange ex, boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append("core_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        if (secure) sb.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    private static String getCookie(HttpExchange ex, String name) {
        if (ex == null || name == null || name.isBlank()) return null;
        List<String> cookieHeaders = ex.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null || cookieHeaders.isEmpty()) return null;
        for (String header : cookieHeaders) {
            if (header == null || header.isBlank()) continue;
            String[] parts = header.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                int idx = trimmed.indexOf('=');
                if (idx <= 0) continue;
                String k = trimmed.substring(0, idx).trim();
                if (!name.equals(k)) continue;
                return trimmed.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private record Session(long expiresAtMs) {}

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static String getQueryToken(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q == null || q.isBlank()) return null;
        for (String part : q.split("&")) {
            if (part.startsWith("token=")) {
                try {
                    return URLDecoder.decode(part.substring("token=".length()), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return part.substring("token=".length());
                }
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange ex, Object obj) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", GSON.toJson(obj));
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
