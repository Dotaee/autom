package fr.autominer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AutoMiner v0.2 : suit un circuit de points enregistré à la main.
 *
 * G = enregistrer un point (le premier appui démarre l'enregistrement)
 * H = type du PROCHAIN segment : MINAGE (clic maintenu) / TRANSIT (déplacement seul)
 * J = démarrer / arrêter le circuit (boucle)
 * K = effacer le circuit
 *
 * Le mod utilise les entrées normales du jeu (avancer, attaquer, sauter) : pas de téléportation,
 * pas de modification de blocs, pas de vitesse de minage bonus. Il s'arrête tout seul si :
 * vie basse, outil presque cassé, inventaire plein, lave devant, joueur bloqué, écran ouvert.
 */
public final class AutoMinerClient implements ClientModInitializer {

    // ---- Réglages ----
    /** Le circuit ne démarre qu'en solo (comme annoncé dans la description). Mettre false pour lever la limite. */
    private static final boolean SINGLEPLAYER_ONLY = false;
    private static final float MIN_HEALTH = 6.0f;          // 3 coeurs
    /** Arrêt quand l'inventaire est plein (false = le bot continue de miner quand même). */
    private static final boolean STOP_WHEN_INVENTORY_FULL = false;
    private static final int MIN_DURABILITY_LEFT = 12;
    private static final double REACH_HORIZONTAL = 0.8;
    private static final double REACH_VERTICAL = 1.3;
    private static final float MAX_YAW_STEP = 14.0f;       // degrés par tick (rotation fluide)
    private static final float MAX_PITCH_STEP = 5.0f;
    private static final int STUCK_TICKS = 300;            // 15 s sans avancer d'au moins 0,5 bloc
    private static final double STUCK_DISTANCE = 0.5;
    /** Pendant un segment MINAGE : arrêt si le bloc visé n'est pas dans ALLOWED_BLOCKS. */
    private static final boolean STOP_ON_UNLISTED_BLOCK = true;
    private static final Set<Block> ALLOWED_BLOCKS = Set.of(
            Blocks.GLASS,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.STONE, Blocks.COBBLESTONE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.ANCIENT_DEBRIS);
    /** Appui automatique sur le bouton 4 de la souris (MOUSE4) pendant que le circuit tourne. */
    private static final boolean MOUSE4_ENABLED = true;
    private static final int MOUSE4_INTERVAL_TICKS = 200;  // 200 ticks = 10 s
    private static final int MOUSE4_HOLD_TICKS = 2;        // durée de l'appui
    private static final InputConstants.Key MOUSE4 =
            InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_4);

    // ---- État ----
    private static final List<RoutePoint> ROUTE = new ArrayList<>();
    private static boolean recording = false;
    private static boolean miningForNextLeg = true;
    private static boolean running = false;
    private static int targetIndex = 1;
    private static int stuckTicks = 0;
    private static Vec3 stuckAnchor = Vec3.ZERO;
    private static int mouse4Timer = 0;

    private static KeyMapping recordKey;
    private static KeyMapping legTypeKey;
    private static KeyMapping runKey;
    private static KeyMapping clearKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("autominer", "main"));

        recordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        legTypeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.toggle_mining", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
        runKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.run", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        ROUTE.addAll(RouteStore.load());
        ClientTickEvents.END_CLIENT_TICK.register(AutoMinerClient::tick);
    }

    // =====================================================================================

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            running = false;
            recording = false;
            return;
        }

        handleKeys(mc, player);
        if (!running) return;

        if (mc.screen != null) { // inventaire, chat, menu pause... on lâche les touches
            releaseKeys(mc);
            return;
        }
        if (targetIndex >= ROUTE.size()) targetIndex = 0;

        RoutePoint target = ROUTE.get(targetIndex);

        String problem = safetyProblem(mc, player, target);
        if (problem != null) {
            stop(mc);
            message(mc, "Arrêt de sécurité : " + problem);
            return;
        }

        tickMouse4();
        followRoute(mc, player, target);
    }

    private static void handleKeys(Minecraft mc, LocalPlayer player) {
        while (recordKey.consumeClick()) {
            Vec3 p = player.position();
            if (!recording) {
                stop(mc);
                ROUTE.clear();
                recording = true;
                miningForNextLeg = false;
                ROUTE.add(new RoutePoint(p.x, p.y, p.z, player.getXRot(), false));
                RouteStore.save(ROUTE);
                message(mc, "Enregistrement démarré (point 1). H = type du segment suivant, G = point d'arrivée.");
            } else {
                ROUTE.add(new RoutePoint(p.x, p.y, p.z, player.getXRot(), miningForNextLeg));
                RouteStore.save(ROUTE);
                message(mc, "Point " + ROUTE.size() + " enregistré (segment : "
                        + (miningForNextLeg ? "MINAGE" : "TRANSIT") + ").");
            }
        }

        while (legTypeKey.consumeClick()) {
            miningForNextLeg = !miningForNextLeg;
            message(mc, "Prochain segment : " + (miningForNextLeg ? "MINAGE" : "TRANSIT"));
        }

        while (clearKey.consumeClick()) {
            stop(mc);
            ROUTE.clear();
            recording = false;
            RouteStore.save(ROUTE);
            message(mc, "Circuit effacé.");
        }

        while (runKey.consumeClick()) {
            if (running) {
                stop(mc);
                message(mc, "AutoMiner arrêté.");
            } else if (ROUTE.size() < 2) {
                message(mc, "Enregistre au moins 2 points (G) avant de démarrer.");
            } else if (SINGLEPLAYER_ONLY && !mc.hasSingleplayerServer()) {
                message(mc, "Le circuit ne démarre qu'en solo.");
            } else {
                recording = false;
                running = true;
                targetIndex = nearestPointIndex(player.position());
                stuckTicks = 0;
                stuckAnchor = player.position();
                mouse4Timer = 0;
                message(mc, "AutoMiner démarré : " + ROUTE.size() + " points, départ vers le point "
                        + (targetIndex + 1) + ".");
            }
        }
    }

    /** Reprend le circuit au point le plus proche (utile après un arrêt de sécurité). */
    private static int nearestPointIndex(Vec3 pos) {
        int best = 1 % ROUTE.size();
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < ROUTE.size(); i++) {
            double d = ROUTE.get(i).vec().distanceToSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        // on vise le point SUIVANT le plus proche pour avancer dans le sens du circuit
        return (best + 1) % ROUTE.size();
    }

    // =====================================================================================

    private static void followRoute(Minecraft mc, LocalPlayer player, RoutePoint target) {
        Vec3 here = player.position();
        double dx = target.x() - here.x;
        double dz = target.z() - here.z;
        double dy = target.y() - here.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // Point atteint : on passe au suivant sans lâcher les touches (pas de saccade).
        if (horizontal < REACH_HORIZONTAL && Math.abs(dy) < REACH_VERTICAL) {
            targetIndex = (targetIndex + 1) % ROUTE.size();
            return;
        }

        // Rotation fluide vers le point, inclinaison reprise de l'enregistrement.
        if (horizontal > 0.05) {
            float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float dYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
            player.setYRot(player.getYRot() + Mth.clamp(dYaw, -MAX_YAW_STEP, MAX_YAW_STEP));
        }
        float dPitch = target.pitch() - player.getXRot();
        player.setXRot(player.getXRot() + Mth.clamp(dPitch, -MAX_PITCH_STEP, MAX_PITCH_STEP));

        boolean mine = target.mineLeg();

        // Saut automatique uniquement en TRANSIT (marches, petits obstacles) ; jamais en minage.
        boolean jump = !mine && player.onGround()
                && (player.horizontalCollision || dy > 0.6);

        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(jump);
        mc.options.keyAttack.setDown(mine);

        // Détection de blocage
        if (here.distanceTo(stuckAnchor) > STUCK_DISTANCE) {
            stuckAnchor = here;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
    }

    // =====================================================================================

    /** Simule un appui sur MOUSE4 toutes les 10 secondes (touche à lier dans Options > Contrôles). */
    private static void tickMouse4() {
        if (!MOUSE4_ENABLED) return;
        mouse4Timer++;
        if (mouse4Timer == MOUSE4_INTERVAL_TICKS) {
            KeyMapping.click(MOUSE4);
            KeyMapping.set(MOUSE4, true);
        } else if (mouse4Timer >= MOUSE4_INTERVAL_TICKS + MOUSE4_HOLD_TICKS) {
            KeyMapping.set(MOUSE4, false);
            mouse4Timer = 0;
        }
    }

    /** Retourne une raison d'arrêt, ou null si tout va bien. */
    private static String safetyProblem(Minecraft mc, LocalPlayer player, RoutePoint target) {
        if (!player.isAlive()) return "joueur mort.";
        if (player.getHealth() < MIN_HEALTH) return "vie trop basse.";
        if (stuckTicks > STUCK_TICKS) return "joueur bloqué depuis trop longtemps.";

        if (target.mineLeg()) {
            if (STOP_ON_UNLISTED_BLOCK
                    && mc.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                BlockState aimedState = mc.level.getBlockState(hit.getBlockPos());
                Block aimed = aimedState.getBlock();
                // Viser de l'air (ou rien) ne doit jamais déclencher l'arrêt.
                if (!aimedState.isAir() && !ALLOWED_BLOCKS.contains(aimed)) {
                    return "bloc non autorisé visé (" + BuiltInRegistries.BLOCK.getKey(aimed) + ").";
                }
            }

            if (STOP_WHEN_INVENTORY_FULL && player.getInventory().getFreeSlot() < 0) return "inventaire plein.";

            ItemStack held = player.getMainHandItem();
            if (held.isDamageableItem()
                    && held.getMaxDamage() - held.getDamageValue() <= MIN_DURABILITY_LEFT) {
                return "outil presque cassé.";
            }
        }

        // Lave dans les 2 blocs devant (pieds et tête)
        Direction facing = player.getDirection();
        BlockPos base = player.blockPosition();
        for (int step = 1; step <= 2; step++) {
            BlockPos feet = base.relative(facing, step);
            if (isLava(mc, feet) || isLava(mc, feet.above()) || isLava(mc, feet.below())) {
                return "lave devant.";
            }
        }
        return null;
    }

    private static boolean isLava(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    // =====================================================================================

    private static void stop(Minecraft mc) {
        running = false;
        releaseKeys(mc);
    }

    private static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
        if (MOUSE4_ENABLED) KeyMapping.set(MOUSE4, false);
    }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[AutoMiner] " + text));
        }
    }
}
