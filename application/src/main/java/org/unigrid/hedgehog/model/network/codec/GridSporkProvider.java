 package org.unigrid.hedgehog.model.network.codec;

import org.unigrid.hedgehog.model.spork.GridSpork;

import java.time.Instant;

/**
 * Enkel stub för GridSporkProvider utan jqwik
 */
public class GridSporkProvider {

    /**
     * Returnerar en GridSpork-instans med givna parametrar.
     */
    public GridSpork provide(GridSpork.Type type,
                              short flags,
                              byte[] signature,
                              Instant time,
                              Instant previousTime) {

        GridSpork spork = GridSpork.create(type);
        spork.setFlags(flags);
        spork.setSignature(signature);
        spork.setTimeStamp(time);
        spork.setPreviousTimeStamp(previousTime);

        return spork;
    }
}