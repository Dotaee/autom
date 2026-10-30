package fr.autominer;

import net.minecraft.world.phys.Vec3;

/**
 * Un point du circuit. {@code mineLeg} décrit le segment qui ARRIVE à ce point :
 * true = MINAGE (clic gauche maintenu), false = TRANSIT (simple déplacement).
 * {@code pitch} est l'inclinaison verticale du viseur au moment de l'enregistrement.
 */
public record RoutePoint(double x, double y, double z, float pitch, boolean mineLeg) {
    public Vec3 vec() {
        return new Vec3(x, y, z);
    }
}
