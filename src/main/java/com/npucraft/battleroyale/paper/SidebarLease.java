package com.npucraft.battleroyale.paper;

import java.util.Objects;
import java.util.Optional;

/** Identity-based ownership. Once another plugin takes over, updates stop for this lobby visit. */
public final class SidebarLease<T> {
    private final T previous,owned;
    private boolean yielded;
    public SidebarLease(T previous,T owned){this.previous=Objects.requireNonNull(previous);this.owned=Objects.requireNonNull(owned);}
    public boolean mayUpdate(T current){if(current!=owned)yielded=true;return !yielded;}
    public Optional<T> restore(T current){return current==owned?Optional.of(previous):Optional.empty();}
}
