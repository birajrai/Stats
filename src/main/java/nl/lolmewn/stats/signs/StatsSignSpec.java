package nl.lolmewn.stats.signs;

import nl.lolmewn.stats.stat.Stat;

import java.util.Collection;
import java.util.UUID;

public class StatsSignSpec {

    private StatsSignPlayerMode playerMode;
    private StatsSignStatMode statMode;

    private Collection<UUID> players;
    private Collection<Stat> stats;

    private int interval;

    public StatsSignSpec(StatsSignPlayerMode playerMode, StatsSignStatMode statMode, Collection<UUID> players, Collection<Stat> stats) {
        this(playerMode, statMode, players, stats, 10);
    }

    public StatsSignSpec(StatsSignPlayerMode playerMode, StatsSignStatMode statMode, Collection<UUID> players,
                         Collection<Stat> stats, int interval) {
        this.playerMode = playerMode;
        this.statMode = statMode;
        this.players = players;
        this.stats = stats;
        this.interval = interval;
    }

    public StatsSignPlayerMode getPlayerMode() {
        return playerMode;
    }

    public StatsSignStatMode getStatMode() {
        return statMode;
    }

    public Collection<UUID> getPlayers() {
        return players;
    }

    public Collection<Stat> getStats() {
        return stats;
    }

    public int getInterval() {
        return interval;
    }
}
