package nl.lolmewn.stats.stat.impl;

import nl.lolmewn.stats.Util;
import nl.lolmewn.stats.player.StatMetaData;
import nl.lolmewn.stats.stat.Stat;

import java.util.Collection;

public class ItemsCraftedStat extends Stat {

    public ItemsCraftedStat() {
        super("Items crafted", "Amount of items crafted");
    }

    @Override
    public Collection<StatMetaData> getMetaData() {
        return Util.listOf(new StatMetaData("world", String.class, true),
                new StatMetaData("type", String.class, true));
    }
}
