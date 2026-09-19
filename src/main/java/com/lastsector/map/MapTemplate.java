package com.lastsector.map;
import com.lastsector.util.Checks;
import java.nio.file.Path;
import java.util.Objects;
/** Metadata for a template; construction performs no filesystem or world operations. */
public record MapTemplate(String id, String displayName, Path templatePath, PlayableArea playableArea) {
    public MapTemplate {
        Checks.text(id, "id"); Checks.text(displayName, "displayName");
        Objects.requireNonNull(templatePath, "templatePath"); Objects.requireNonNull(playableArea, "playableArea");
    }
}

