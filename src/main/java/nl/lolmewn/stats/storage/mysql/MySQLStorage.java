package nl.lolmewn.stats.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import nl.lolmewn.stats.Util;
import nl.lolmewn.stats.player.*;
import nl.lolmewn.stats.stat.Stat;
import nl.lolmewn.stats.stat.StatManager;
import nl.lolmewn.stats.storage.StorageManager;
import nl.lolmewn.stats.storage.mysql.impl.*;
import nl.lolmewn.stats.storage.mysql.upgrade.MySQLUpgrader;

import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public class MySQLStorage extends StorageManager {

    private final HikariDataSource dataSource;
    private Map<Stat, StatMySQLHandler> handlers = new HashMap<>();
    private CompositeDisposable disposable;

    private static final Logger LOG = Logger.getLogger(MySQLStorage.class.getName());

    public MySQLStorage(MySQLConfig config) throws SQLException, IOException {
        this.LOG.info("Starting MySQL Storage Engine...");
        this.disposable = new CompositeDisposable();
        HikariConfig hcnf = new HikariConfig();
        hcnf.setJdbcUrl(config.getJdbcUrl());
        hcnf.setUsername(config.getUsername());
        hcnf.setPassword(config.getPassword());
        this.dataSource = new HikariDataSource(hcnf);
        try {
            this.LOG.info("Checking MySQL connection...");
            checkConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Connection could not be established, please check the MySQL config", e);
        }

        this.registerHandlers();
        this.checkTableUpgrades();
        this.LOG.info("MySQL ready to go!");
        this.disposable.add(PlayerManager.getInstance().subscribe(this.getPlayerConsumer(), Util::handleError));
    }

    public void shutdown() {
        if (this.disposable != null && !this.disposable.isDisposed()) this.disposable.dispose();
        if (this.dataSource != null && this.dataSource.isRunning()) this.dataSource.close();
    }

    private void checkTableUpgrades() throws SQLException, IOException {
        try (Connection connection = this.getConnection()) {
            new MySQLUpgrader(connection);
        }
    }

    private Consumer<StatsPlayer> getPlayerConsumer() {
        return player -> {
            this.LOG.info("New player triggered: " + player.getUuid().toString());
            player.getContainers().forEach(cont -> // Listen to updates of already-in-place containers
                    this.disposable.add(cont.subscribe(this.getStatTimeEntryConsumer(player, cont), Util::handleError)));
            this.disposable.add(player.subscribe(this.getContainerConsumer(player), Util::handleError)); // Listen to new containers
        };
    }

    private Consumer<StatsContainer> getContainerConsumer(StatsPlayer player) {
        return statsContainer ->
                this.disposable.add(statsContainer.getPublishSubject().subscribe(
                        this.getStatTimeEntryConsumer(player, statsContainer), Util::handleError)
                );
    }

    private Consumer<StatTimeEntry> getStatTimeEntryConsumer(StatsPlayer player, StatsContainer statsContainer) {
        if (!(player instanceof MySQLStatsPlayer)) {
            return null;
        }
        return statTimeEntry -> this.disposable.add(Flowable.just(statTimeEntry).subscribeOn(Schedulers.io())
                .subscribe((entry) -> this.storeEntry((MySQLStatsPlayer) player, statsContainer, entry), Util::handleError));
    }

    private void registerHandlers() {
        StatManager.getInstance().getStat("Blocks broken").ifPresent(stat -> this.handlers.put(stat, new BlockStorage(true)));
        StatManager.getInstance().getStat("Blocks placed").ifPresent(stat -> this.handlers.put(stat, new BlockStorage(false)));
        StatManager.getInstance().getStat("Deaths").ifPresent(stat -> this.handlers.put(stat, new DeathStorage()));
        StatManager.getInstance().getStat("Kills").ifPresent(stat -> this.handlers.put(stat, new KillStorage()));
        StatManager.getInstance().getStat("Last join").ifPresent(stat -> this.handlers.put(stat, new LastJoinStorage()));
        StatManager.getInstance().getStat("Last quit").ifPresent(stat -> this.handlers.put(stat, new LastQuitStorage()));
        StatManager.getInstance().getStat("Trades performed").ifPresent(stat -> this.handlers.put(stat, new TradesPerformedStorage()));

        // Register all other stats to the default
        StatManager.getInstance().getStats().stream()
                .filter(stat -> !this.handlers.containsKey(stat))
                .forEach(stat -> {
                    if (stat.getMetaData().stream().anyMatch(meta -> meta.getId().equalsIgnoreCase("type"))) {
                        this.handlers.put(stat, new TypedStatStorage(stat));
                    } else {
                        this.handlers.put(stat, new GeneralStatStorage(stat));
                    }
                });
    }

    private void storeEntry(MySQLStatsPlayer player, StatsContainer container, StatTimeEntry entry) throws SQLException {
        if (this.handlers.containsKey(container.getStat())) {
            try (Connection con = this.getConnection()) {
                try {
                    this.handlers.get(container.getStat()).storeEntry(con, player, container, entry);
                } catch (SQLException ex) {
                    this.LOG.warning(
                        String.format(
                            "Error occurred when trying to save %s data for player %s, full error below.",
                            container.getStat().getName(), player.getUuid().toString()
                        )
                    );
                    throw ex; // rethrow
                }
            }
        }
    }

    private void checkConnection() throws SQLException {
        try (Connection con = getConnection()) {
            con.createStatement().execute("SELECT 1");
        }
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    @Override
    public Callable<StatsPlayer> loadPlayer(UUID uuid) {
        return () -> {
            StatsPlayer statsPlayer = new MySQLStatsPlayer(uuid, -1);
            this.internalLoadPlayer(statsPlayer);
            return statsPlayer;
        };
    }

    @Override
    public void internalLoadPlayer(StatsPlayer player) {
        if (!(player instanceof MySQLStatsPlayer)) {
            throw new IllegalArgumentException("Supplied StatsPlayer is not a MySQLStatsPlayer");
        }
        try (Connection con = this.getConnection()) {
            this.loadPlayerId(con, (MySQLStatsPlayer) player);
            for (Map.Entry<Stat, StatMySQLHandler> mapEntry : this.handlers.entrySet()) {
                for (StatTimeEntry entry : mapEntry.getValue().loadEntries(con, player.getUuid())) {
                    player.getStats(mapEntry.getKey()).addEntry(entry);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadPlayerId(Connection con, MySQLStatsPlayer player) throws SQLException {
        try (PreparedStatement st = con.prepareStatement("SELECT id FROM stats_players WHERE uuid=UNHEX(?)")) {
            st.setString(1, player.getUuid().toString().replace("-", ""));
            ResultSet set = st.executeQuery();
            if (set != null && set.next()) {
                player.setDbId(set.getInt("id"));
                return;
            }
            // No player, insert and get generated id
            PreparedStatement insert =
                    con.prepareStatement("INSERT INTO stats_players (uuid) VALUE (UNHEX(?))", Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, player.getUuid().toString().replace("-", ""));
            insert.execute();
            ResultSet generated = insert.getGeneratedKeys();
            if (generated != null && generated.next()) {
                player.setDbId(generated.getInt(1));
                return;
            }
            throw new IllegalStateException("No player exists but player ID could not be generated either");
        }
    }

    public void onPlayerJoin(UUID uniqueId, String name) {
        try (Connection con = this.getConnection()) {
            PreparedStatement st = con.prepareStatement("UPDATE stats_players SET username=? WHERE uuid=UNHEX(?)");
            st.setString(1, name);
            st.setString(2, uniqueId.toString().replace("-", ""));
            st.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
