package ac.grim.grimac.predictionengine;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.movement.EntityControl;
import ac.grim.grimac.checks.type.PositionCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.movementtick.MovementTickerHorse;
import ac.grim.grimac.predictionengine.movementtick.MovementTickerPig;
import ac.grim.grimac.predictionengine.movementtick.MovementTickerPlayer;
import ac.grim.grimac.predictionengine.movementtick.MovementTickerStrider;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineNormal;
import ac.grim.grimac.predictionengine.predictions.rideable.BoatPredictionEngine;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.AlmostBoolean;
import ac.grim.grimac.utils.data.PredictionData;
import ac.grim.grimac.utils.data.SetBackData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.data.packetentity.PacketEntityRideable;
import ac.grim.grimac.utils.enums.EntityType;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsImplementations.*;
import ac.grim.grimac.utils.threads.CustomThreadPoolExecutor;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.retrooper.packetevents.utils.player.ClientVersion;
import io.github.retrooper.packetevents.utils.player.Hand;
import io.github.retrooper.packetevents.utils.server.ServerVersion;
import io.github.retrooper.packetevents.utils.vector.Vector3d;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// This class is how we manage to safely do everything async
// AtomicInteger allows us to make decisions safely - we can get and set values in one processor instruction
// This is the meaning of GrimPlayer.tasksNotFinished
// Stage 0 - All work is done
// Stage 1 - There is more work, number = number of jobs in the queue and running
//
// After finishing doing the predictions:
// If stage 0 - Do nothing
// If stage 1 - Subtract by 1, and add another to the queue
//
// When the player sends a packet and we have to add him to the queue:
// If stage 0 - Add one and add the data to the workers
// If stage 1 - Add the data to the queue and add one
public class MovementCheckRunner extends PositionCheck {
    private static final Material CARROT_ON_A_STICK = XMaterial.CARROT_ON_A_STICK.parseMaterial();
    private static final Material WARPED_FUNGUS_ON_A_STICK = XMaterial.WARPED_FUNGUS_ON_A_STICK.parseMaterial();
    private static final Material BUBBLE_COLUMN = XMaterial.BUBBLE_COLUMN.parseMaterial();
    public static CustomThreadPoolExecutor executor =
            new CustomThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new ThreadFactoryBuilder().setDaemon(true).build());
    public static ConcurrentLinkedQueue<PredictionData> waitingOnServerQueue = new ConcurrentLinkedQueue<>();

    public MovementCheckRunner(GrimPlayer player) {
        super(player);
    }

    public void processAndCheckMovementPacket(PredictionData data) {
        // The player is in an unloaded chunk and didn't teleport
        // OR
        // This teleport wasn't valid as the player STILL hasn't loaded this damn chunk.
        // Keep re-teleporting until they load the chunk!
        if (player.getSetbackTeleportUtil().insideUnloadedChunk()) {
            // Teleport the player back to avoid players being able to simply ignore transactions
            player.getSetbackTeleportUtil().executeForceResync();
            return;
        }

        boolean forceAddThisTask = data.inVehicle || data.isJustTeleported;

        PredictionData nextTask = data.player.nextTaskToRun;

        if (forceAddThisTask) { // Run the check now
            data.player.nextTaskToRun = null;
            if (nextTask != null)
                addData(nextTask);
            addData(data);
        } else if (nextTask != null) {
            // Mojang fucked up packet order so we need to fix the current item held
            //
            // Why would you send the item held AFTER you send their movement??? Anyways. fixed. you're welcome
            nextTask.itemHeld = data.itemHeld;
            // This packet was a duplicate to the current one, ignore it.
            // Thank you 1.17 for sending duplicate positions!
            if (nextTask.playerX != data.playerX || nextTask.playerY != data.playerY || nextTask.playerZ != data.playerZ) {
                data.player.nextTaskToRun = data;
                addData(nextTask);
            }
        } else {
            data.player.nextTaskToRun = data;
        }
    }

    private void addData(PredictionData data) {
        if (data.player.tasksNotFinished.getAndIncrement() == 0) {
            executor.runCheck(data);
        } else {
            data.player.queuedPredictions.add(data);
        }
    }

    public void runTransactionQueue(GrimPlayer player) {
        // This takes < 0.01 ms to run world and entity updates
        // It stops a memory leak from all the lag compensation queue'ing and never ticking
        CompletableFuture.runAsync(() -> {
            // It is unsafe to modify the transaction world async if another check is running
            // Adding 1 to the tasks blocks another check from running
            //
            // If there are no tasks queue'd, it is safe to modify these variables
            //
            // Additionally, we don't want to, and it isn't needed, to update the world
            if (player.tasksNotFinished.compareAndSet(0, 1)) {
                int lastTransaction = player.packetStateData.packetLastTransactionReceived.get();
                player.compensatedWorld.tickUpdates(lastTransaction);
                player.latencyUtils.handleAnticheatSyncTransaction(lastTransaction);
                player.compensatedEntities.tickUpdates(lastTransaction);
                player.compensatedFlying.canFlyLagCompensated(lastTransaction);
                player.compensatedFireworks.getMaxFireworksAppliedPossible();
                player.compensatedRiptide.getCanRiptide();
                player.compensatedElytra.isGlidingLagCompensated(lastTransaction);

                // As we incremented the tasks, we must now execute the next task, if there is one
                executor.queueNext(player);
            }
        }, executor);
    }

    public void check(PredictionData data) {
        GrimPlayer player = data.player;

        data.isCheckNotReady = data.minimumTickRequiredToContinue > GrimAPI.INSTANCE.getTickManager().getTick();
        if (data.isCheckNotReady) {
            return;
        }

        // Note this before any updates
        boolean byGround = !Collisions.isEmpty(player, player.boundingBox.copy().expand(0.03, 0, 0.03).offset(0, -0.03, 0));

        player.uncertaintyHandler.stuckOnEdge--;
        player.uncertaintyHandler.lastStuckEast--;
        player.uncertaintyHandler.lastStuckWest--;
        player.uncertaintyHandler.lastStuckSouth--;
        player.uncertaintyHandler.lastStuckNorth--;

        // This must be done before updating the world to support bridging and sneaking at the edge of it
        if ((player.isSneaking || player.wasSneaking) && player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree) {
            // Before we do player block placements, determine if the shifting glitch occurred
            // The 0.03 and maintaining velocity is just brutal
            boolean isEast = Collisions.maybeBackOffFromEdge(new Vector(0.1, 0, 0), player, true).getX() != 0.1;
            boolean isWest = Collisions.maybeBackOffFromEdge(new Vector(-0.1, 0, 0), player, true).getX() != -0.1;
            boolean isSouth = Collisions.maybeBackOffFromEdge(new Vector(0, 0, 0.1), player, true).getZ() != 0.1;
            boolean isNorth = Collisions.maybeBackOffFromEdge(new Vector(0, 0, -0.1), player, true).getZ() != -0.1;

            if (isEast) player.uncertaintyHandler.lastStuckEast = 0;
            if (isWest) player.uncertaintyHandler.lastStuckWest = 0;
            if (isSouth) player.uncertaintyHandler.lastStuckSouth = 0;
            if (isNorth) player.uncertaintyHandler.lastStuckNorth = 0;

            if (player.uncertaintyHandler.lastStuckEast > -3)
                player.uncertaintyHandler.xPositiveUncertainty += player.speed;

            if (player.uncertaintyHandler.lastStuckWest > -3)
                player.uncertaintyHandler.xNegativeUncertainty -= player.speed;

            if (player.uncertaintyHandler.lastStuckNorth > -3)
                player.uncertaintyHandler.zNegativeUncertainty -= player.speed;

            if (player.uncertaintyHandler.lastStuckSouth > -3)
                player.uncertaintyHandler.zPositiveUncertainty += player.speed;

            if (isEast || isWest || isSouth || isNorth) {
                player.uncertaintyHandler.stuckOnEdge = 0;
            }
        }

        player.lastTransactionReceived = data.lastTransaction;
        if (!data.isJustTeleported) player.movementPackets++;

        // Tick updates AFTER updating bounding box and actual movement
        player.compensatedWorld.tickUpdates(data.lastTransaction);
        player.compensatedWorld.tickPlayerInPistonPushingArea();
        player.latencyUtils.handleAnticheatSyncTransaction(data.lastTransaction);

        // Update entities to get current vehicle
        player.compensatedEntities.tickUpdates(data.lastTransaction);

        // Tick player vehicle after we update the packet entity state
        player.playerVehicle = player.vehicle == null ? null : player.compensatedEntities.getEntity(player.vehicle);
        player.inVehicle = player.playerVehicle != null;

        // Update knockback and explosions after getting the vehicle
        player.firstBreadKB = player.checkManager.getKnockbackHandler().getFirstBreadOnlyKnockback(player.inVehicle ? player.vehicle : player.entityID, data.lastTransaction);
        player.likelyKB = player.checkManager.getKnockbackHandler().getRequiredKB(player.inVehicle ? player.vehicle : player.entityID, data.lastTransaction);

        player.firstBreadExplosion = player.checkManager.getExplosionHandler().getFirstBreadAddedExplosion(data.lastTransaction);
        player.likelyExplosions = player.checkManager.getExplosionHandler().getPossibleExplosions(data.lastTransaction);

        // The game's movement is glitchy when switching between vehicles
        player.vehicleData.lastVehicleSwitch++;
        if (player.lastVehicle != player.playerVehicle) {
            player.vehicleData.lastVehicleSwitch = 0;
        }
        // It is also glitchy when switching between client vs server vehicle control
        if (player.vehicleData.lastDummy) {
            player.vehicleData.lastVehicleSwitch = 0;
        }
        player.vehicleData.lastDummy = false;

        if (player.vehicleData.lastVehicleSwitch < 5) {
            player.checkManager.getExplosionHandler().forceExempt();
            player.checkManager.getKnockbackHandler().forceExempt();
        }

        // Wtf, why does the player send vehicle packets when not in vehicle, I don't understand this part of shitty netcode

        // If the check was for players moving in a vehicle, but after we just updated vehicles
        // the player isn't in a vehicle, don't check.
        if (data.inVehicle && player.vehicle == null) {
            return;
        }

        // If the check was for a player out of a vehicle but the player is in a vehicle
        if (!data.inVehicle && player.vehicle != null) {
            return;
        }

        if (player.playerVehicle != player.lastVehicle) {
            data.isJustTeleported = true;

            if (player.playerVehicle != null) {
                Vector3d pos = new Vector3d(data.playerX, data.playerY, data.playerZ);
                double distOne = pos.distance(player.playerVehicle.position);
                double distTwo = pos.distance(player.playerVehicle.lastTickPosition);

                // Stop players from teleporting when they enter a vehicle
                // Is this a cheat?  Do we have to lower this threshold?
                // Until I see evidence that this cheat exists, I am keeping this lenient.
                if (distOne > 1 && distTwo > 1) {
                    player.getSetbackTeleportUtil().executeForceResync();
                }
            }
        }

        player.lastVehicle = player.playerVehicle;

        if (player.isInBed != player.lastInBed) {
            data.isJustTeleported = true;
        }
        player.lastInBed = player.isInBed;

        // Teleporting is not a tick, don't run anything that we don't need to, to avoid falses
        player.uncertaintyHandler.lastTeleportTicks--;
        if (data.isJustTeleported) {
            player.x = data.playerX;
            player.y = data.playerY;
            player.z = data.playerZ;
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;
            player.uncertaintyHandler.lastTeleportTicks = 0;

            // Reset velocities
            // Teleporting a vehicle does not reset its velocity
            if (!player.inVehicle) {
                player.clientVelocity = new Vector();
            }

            player.lastWasClimbing = 0;
            player.canSwimHop = false;

            // Teleports OVERRIDE explosions and knockback
            player.checkManager.getExplosionHandler().forceExempt();
            player.checkManager.getExplosionHandler().handlePlayerExplosion(0);
            player.checkManager.getKnockbackHandler().forceExempt();
            player.checkManager.getKnockbackHandler().handlePlayerKb(0);

            // Manually call prediction complete to handle teleport
            player.getSetbackTeleportUtil().onPredictionComplete(new PredictionComplete(0, data));
            // Issues with ghost blocks should now be resolved
            player.getSetbackTeleportUtil().confirmPredictionTeleport();

            player.uncertaintyHandler.lastHorizontalOffset = 0;
            player.uncertaintyHandler.lastVerticalOffset = 0;

            return;
        }

        // Don't check sleeping players
        if (player.isInBed) return;

        if (!player.inVehicle) {
            player.speed = player.compensatedEntities.playerEntityMovementSpeed;
            player.hasGravity = player.playerEntityHasGravity;
        }

        // Check if the player can control their horse, if they are on a horse
        //
        // Player cannot control entities if other players are doing so, although the server will just
        // ignore these bad packets
        // Players cannot control stacked vehicles
        // Again, the server knows to ignore this
        //
        // Therefore, we just assume that the client and server are modded or whatever.
        if (player.inVehicle) {
            // Players are unable to take explosions in vehicles
            player.checkManager.getExplosionHandler().forceExempt();

            // When in control of the entity, the player sets the entity position to their current position
            player.playerVehicle.lastTickPosition = player.playerVehicle.position;
            player.playerVehicle.position = new Vector3d(player.x, player.y, player.z);

            player.hasGravity = player.playerVehicle.hasGravity;

            ItemStack mainHand = player.bukkitPlayer.getInventory().getItem(data.itemHeld);
            // For whatever reason the vehicle move packet occurs AFTER the player changes slots...
            ItemStack newMainHand = player.bukkitPlayer.getInventory().getItem(player.packetStateData.lastSlotSelected);
            if (player.playerVehicle instanceof PacketEntityRideable) {
                EntityControl control = ((EntityControl) player.checkManager.getPostPredictionCheck(EntityControl.class));

                Material requiredItem = player.playerVehicle.type == EntityType.PIG ? CARROT_ON_A_STICK : WARPED_FUNGUS_ON_A_STICK;
                if ((mainHand == null || mainHand.getType() != requiredItem) &&
                        (ServerVersion.getVersion().isNewerThanOrEquals(ServerVersion.v_1_9)
                                && player.bukkitPlayer.getInventory().getItemInOffHand().getType() != requiredItem) &&
                        (newMainHand == null || newMainHand.getType() != requiredItem)) {
                    // Entity control cheats!  Set the player back
                    if (control.flag()) {
                        player.getSetbackTeleportUtil().executeSetback();
                    }
                } else {
                    control.rewardPlayer();
                }

                if (player.playerVehicle != player.lastVehicle) {
                    // Hack with boostable ticking without us (why does it do this?)
                    ((PacketEntityRideable) player.playerVehicle).currentBoostTime += 4;
                }
            }
        }

        // Determine whether the player is being slowed by using an item
        // Handle the player dropping food to stop eating
        // We are sync'd to roughly the bukkit thread here
        // Although we don't have inventory lag compensation so we can't fully sync
        // Works unless the player spams their offhand button
        ItemStack mainHand = player.bukkitPlayer.getInventory().getItem(data.itemHeld);
        ItemStack offHand = XMaterial.supports(9) ? player.bukkitPlayer.getInventory().getItemInOffHand() : null;
        if (data.isUsingItem == AlmostBoolean.TRUE && (mainHand == null || !Materials.isUsable(mainHand.getType())) &&
                (offHand == null || !Materials.isUsable(offHand.getType()))) {
            data.isUsingItem = AlmostBoolean.MAYBE;
        }

        player.ticksSinceLastSlotSwitch++;
        player.tickSinceLastOffhand++;
        // Switching items results in the player no longer using an item
        if (data.itemHeld != player.lastSlotSelected && data.usingHand == Hand.MAIN_HAND) {
            player.ticksSinceLastSlotSwitch = 0;
        }

        // See shields without this, there's a bit of a delay before the slow applies.  Not sure why.  I blame Mojang.
        if (player.ticksSinceLastSlotSwitch < 3 || player.tickSinceLastOffhand < 5)
            data.isUsingItem = AlmostBoolean.MAYBE;

        // Temporary hack so players can get slowed speed even when not using an item, when we aren't certain
        // TODO: This shouldn't be needed if we latency compensate inventories
        if (data.isUsingItem == AlmostBoolean.FALSE) data.isUsingItem = AlmostBoolean.MAYBE;

        player.isUsingItem = data.isUsingItem;

        player.uncertaintyHandler.lastFlyingTicks++;
        if (player.isFlying) {
            player.fallDistance = 0;
            player.uncertaintyHandler.lastFlyingTicks = 0;
        }

        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.lastX, player.lastY, player.lastZ);

        player.x = data.playerX;
        player.y = data.playerY;
        player.z = data.playerZ;
        player.xRot = data.xRot;
        player.yRot = data.yRot;

        player.onGround = data.onGround;

        player.lastSprinting = player.isSprinting;
        player.wasFlying = player.isFlying;
        player.wasGliding = player.isGliding;
        player.lastRiptidePose = player.isRiptidePose;
        player.wasSwimming = player.isSwimming;
        player.isSprinting = data.isSprinting;
        player.wasSneaking = player.isSneaking;
        player.isSneaking = data.isSneaking;
        player.isClimbing = Collisions.onClimbable(player, player.lastX, player.lastY, player.lastZ);

        player.isFlying = player.compensatedFlying.canFlyLagCompensated(data.lastTransaction);
        player.isGliding = player.compensatedElytra.isGlidingLagCompensated(data.lastTransaction) && !player.isFlying;
        player.specialFlying = player.onGround && !player.isFlying && player.wasFlying || player.isFlying;
        player.isRiptidePose = player.compensatedRiptide.getPose(data.lastTransaction);

        player.lastSlotSelected = data.itemHeld;
        player.tryingToRiptide = data.isTryingToRiptide;

        player.minPlayerAttackSlow = data.minPlayerAttackSlow;
        player.maxPlayerAttackSlow = data.maxPlayerAttackSlow;

        player.clientControlledVerticalCollision = Math.abs(player.y % (1 / 64D)) < 0.00001;
        // If you really have nothing better to do, make this support offset blocks like bamboo.  Good luck!
        player.clientControlledHorizontalCollision = Math.min(GrimMath.distanceToHorizontalCollision(player.x), GrimMath.distanceToHorizontalCollision(player.z)) < 1e-6;

        player.uncertaintyHandler.lastSneakingChangeTicks--;
        if (player.isSneaking != player.wasSneaking)
            player.uncertaintyHandler.lastSneakingChangeTicks = 0;

        // This isn't the final velocity of the player in the tick, only the one applied to the player
        player.actualMovement = new Vector(player.x - player.lastX, player.y - player.lastY, player.z - player.lastZ);

        // ViaVersion messes up flight speed for 1.7 players
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.v_1_7_10) && player.isFlying)
            player.isSprinting = true;

        // Stop stuff like clients using elytra in a vehicle...
        // Interesting, on a pig or strider, a player can climb a ladder
        if (player.inVehicle) {
            // Reset fall distance when riding
            player.fallDistance = 0;
            player.isFlying = false;
            player.isGliding = false;
            player.specialFlying = false;

            if (player.playerVehicle.type != EntityType.PIG && player.playerVehicle.type != EntityType.STRIDER) {
                player.isClimbing = false;
            }
        }

        // Multiplying by 1.3 or 1.3f results in precision loss, you must multiply by 0.3
        player.speed += player.isSprinting ? player.speed * 0.3f : 0;

        player.jumpAmplifier = player.compensatedPotions.getPotionLevel("JUMP");
        player.levitationAmplifier = player.compensatedPotions.getPotionLevel("LEVITATION");
        player.slowFallingAmplifier = player.compensatedPotions.getPotionLevel("SLOW_FALLING");
        player.dolphinsGraceAmplifier = player.compensatedPotions.getPotionLevel("DOLPHINS_GRACE");

        player.flySpeed = data.flySpeed;

        player.uncertaintyHandler.wasLastOnGroundUncertain = false;

        player.uncertaintyHandler.lastGlidingChangeTicks--;
        if (player.isGliding != player.wasGliding) player.uncertaintyHandler.lastGlidingChangeTicks = 0;

        player.uncertaintyHandler.isSteppingOnSlime = Collisions.hasSlimeBlock(player);
        player.uncertaintyHandler.wasSteppingOnBouncyBlock = player.uncertaintyHandler.isSteppingOnBouncyBlock;
        player.uncertaintyHandler.isSteppingOnBouncyBlock = Collisions.hasBouncyBlock(player);
        player.uncertaintyHandler.isSteppingOnIce = Materials.checkFlag(BlockProperties.getOnBlock(player, player.lastX, player.lastY, player.lastZ), Materials.ICE_BLOCKS);
        player.uncertaintyHandler.isSteppingNearBubbleColumn = player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_13) && Collisions.hasMaterial(player, BUBBLE_COLUMN, -1);

        // Update firework end/start uncertainty
        player.uncertaintyHandler.lastFireworkStatusChange--;
        boolean hasFirework = (player.isGliding || player.wasGliding) && player.compensatedFireworks.getMaxFireworksAppliedPossible() > 0;
        if (hasFirework != player.uncertaintyHandler.lastUsingFirework)
            player.uncertaintyHandler.lastFireworkStatusChange = 0;
        player.uncertaintyHandler.lastUsingFirework = hasFirework;

        SimpleCollisionBox expandedBB = GetBoundingBox.getBoundingBoxFromPosAndSize(player.lastX, player.lastY, player.lastZ, 0.001, 0.001);

        // Don't expand if the player moved more than 50 blocks this tick (stop netty crash exploit)
        if (player.actualMovement.lengthSquared() < 2500)
            expandedBB.expandToAbsoluteCoordinates(player.x, player.y, player.z);

        expandedBB.expand(Pose.STANDING.width / 2, 0, Pose.STANDING.width / 2);
        expandedBB.expandMax(0, Pose.STANDING.height, 0);

        // if the player is using a version with glitched chest and anvil bounding boxes,
        // and they are intersecting with these glitched bounding boxes
        // give them a decent amount of uncertainty and don't ban them for mojang's stupid mistake
        boolean isGlitchy = player.uncertaintyHandler.isNearGlitchyBlock;
        player.uncertaintyHandler.isNearGlitchyBlock = player.getClientVersion().isOlderThan(ClientVersion.v_1_9) && Collisions.hasMaterial(player, expandedBB.copy().expand(0.03), checkData -> Materials.isAnvil(checkData.getMaterial()) || Materials.isWoodenChest(checkData.getMaterial()));
        player.uncertaintyHandler.isOrWasNearGlitchyBlock = isGlitchy || player.uncertaintyHandler.isNearGlitchyBlock;

        player.uncertaintyHandler.scaffoldingOnEdge = player.uncertaintyHandler.nextTickScaffoldingOnEdge;
        player.uncertaintyHandler.checkForHardCollision();

        player.uncertaintyHandler.lastFlyingStatusChange--;
        if (player.isFlying != player.wasFlying) player.uncertaintyHandler.lastFlyingStatusChange = 0;

        player.uncertaintyHandler.lastThirtyMillionHardBorder--;
        if (!player.inVehicle && (Math.abs(player.x) == 2.9999999E7D || Math.abs(player.z) == 2.9999999E7D)) {
            player.uncertaintyHandler.lastThirtyMillionHardBorder = 0;
        }

        player.uncertaintyHandler.lastUnderwaterFlyingHack--;
        if (player.specialFlying && player.getClientVersion().isOlderThan(ClientVersion.v_1_13) && player.compensatedWorld.containsLiquid(player.boundingBox)) {
            player.uncertaintyHandler.lastUnderwaterFlyingHack = 0;
        }

        player.uncertaintyHandler.claimingLeftStuckSpeed = player.stuckSpeedMultiplier.getX() < 1 && !Collisions.checkStuckSpeed(player);

        Vector backOff = Collisions.maybeBackOffFromEdge(player.clientVelocity, player, true);
        player.uncertaintyHandler.nextTickScaffoldingOnEdge = player.clientVelocity.getX() != 0 && player.clientVelocity.getZ() != 0 && backOff.getX() == 0 && backOff.getZ() == 0;
        player.canGroundRiptide = false;
        Vector oldClientVel = player.clientVelocity;

        boolean wasChecked = false;

        // Exempt if the player is offline
        if (player.isDead || (player.playerVehicle != null && player.playerVehicle.isDead)) {
            // Dead players can't cheat, if you find a way how they could, open an issue
            player.predictedVelocity = new VectorData(player.actualMovement, VectorData.VectorType.Dead);
            player.clientVelocity = new Vector();
        } else if ((ServerVersion.getVersion().isNewerThanOrEquals(ServerVersion.v_1_8) && data.gameMode == GameMode.SPECTATOR) || player.specialFlying) {
            // We could technically check spectator but what's the point...
            // Added complexity to analyze a gamemode used mainly by moderators
            //
            // TODO: Re-implement flying support
            player.predictedVelocity = new VectorData(player.actualMovement, VectorData.VectorType.Spectator);
            player.clientVelocity = player.actualMovement.clone();
            player.gravity = 0;
            player.friction = 0.91f;
            PredictionEngineNormal.staticVectorEndOfTick(player, player.clientVelocity);
        } else if (player.playerVehicle == null) {
            wasChecked = true;

            // Depth strider was added in 1.8
            ItemStack boots = player.bukkitPlayer.getInventory().getBoots();
            if (boots != null && XMaterial.supports(8) && player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_8)) {
                player.depthStriderLevel = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            } else {
                player.depthStriderLevel = 0;
            }

            // Now that we have all the world updates, recalculate if the player is near the ground
            player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree = !Collisions.isEmpty(player, player.boundingBox.copy().expand(0.03, 0, 0.03).offset(0, -0.03, 0));
            player.uncertaintyHandler.didGroundStatusChangeWithoutPositionPacket = data.didGroundStatusChangeWithoutPositionPacket;

            // Vehicles don't have jumping or that stupid < 0.03 thing
            // If the player isn't on the ground, a packet in between < 0.03 said they did
            // And the player is reasonably touching the ground
            //
            // And the player isn't now near the ground due to a new block placed by the player
            //
            // Give some lenience and update the onGround status
            if (player.uncertaintyHandler.didGroundStatusChangeWithoutPositionPacket && !player.lastOnGround
                    && (player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree || byGround)
                    // Restrict allowed 0.03 - patches fast towering bypass
                    && player.clientVelocity.getY() < 0.03) {
                player.lastOnGround = true;
                player.uncertaintyHandler.wasLastOnGroundUncertain = true;
                player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree = true;
                player.clientClaimsLastOnGround = true;
            }

            // This is wrong and the engine was not designed around stuff like this
            player.canGroundRiptide = ((player.clientClaimsLastOnGround && player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree)
                    || (player.uncertaintyHandler.isSteppingOnSlime && player.uncertaintyHandler.lastTickWasNearGroundZeroPointZeroThree))
                    && player.tryingToRiptide && player.compensatedRiptide.getCanRiptide() && !player.inVehicle;
            player.verticalCollision = false;

            // Riptiding while on the ground moves the hitbox upwards before any movement code runs
            // It's a pain to support and this is my best attempt
            if (player.canGroundRiptide) {
                Vector pushingMovement = Collisions.collide(player, 0, 1.1999999F, 0);
                player.verticalCollision = pushingMovement.getY() != 1.1999999F;
                double currentY = player.clientVelocity.getY();
                player.uncertaintyHandler.slimeBlockUpwardsUncertainty.add(Math.abs(Riptide.getRiptideVelocity(player).getY()) + (currentY > 0 ? currentY : 0));

                // If the player was very likely to have used riptide on the ground
                // (Patches issues with slime and other desync's)
                if (likelyGroundRiptide(pushingMovement)) {
                    player.lastOnGround = false;
                    player.boundingBox.offset(0, pushingMovement.getY(), 0);
                    player.lastY += pushingMovement.getY();
                    player.actualMovement = new Vector(player.x - player.lastX, player.y - player.lastY, player.z - player.lastZ);

                    Collisions.handleInsideBlocks(player);
                }
            } else {
                if (player.uncertaintyHandler.influencedByBouncyBlock()) { // Slime
                    player.uncertaintyHandler.slimeBlockUpwardsUncertainty.add(player.clientVelocity.getY());
                } else {
                    player.uncertaintyHandler.slimeBlockUpwardsUncertainty.add(0d);
                }
            }

            new PlayerBaseTick(player).doBaseTick();
            new MovementTickerPlayer(player).livingEntityAIStep();

            // 0.03 is rare with gliding, so, therefore, to try and patch falses, we should update with the vanilla order
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_14) && (player.isGliding || player.wasGliding)) {
                new PlayerBaseTick(player).updatePlayerPose();
            }

        } else if (ServerVersion.getVersion().isNewerThanOrEquals(ServerVersion.v_1_9) && player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_9)) {
            wasChecked = true;
            // The player and server are both on a version with client controlled entities
            // If either or both of the client server version has server controlled entities
            // The player can't use entities (or the server just checks the entities)
            if (player.playerVehicle.type == EntityType.BOAT) {
                new PlayerBaseTick(player).doBaseTick();
                // Speed doesn't affect anything with boat movement
                new BoatPredictionEngine(player).guessBestMovement(0, player);
            } else if (player.playerVehicle instanceof PacketEntityHorse) {
                new PlayerBaseTick(player).doBaseTick();
                new MovementTickerHorse(player).livingEntityAIStep();
            } else if (player.playerVehicle.type == EntityType.PIG) {
                new PlayerBaseTick(player).doBaseTick();
                new MovementTickerPig(player).livingEntityAIStep();
            } else if (player.playerVehicle.type == EntityType.STRIDER) {
                new PlayerBaseTick(player).doBaseTick();
                new MovementTickerStrider(player).livingEntityAIStep();
                MovementTickerStrider.floatStrider(player);
                Collisions.handleInsideBlocks(player);
            } else {
                wasChecked = false;
            }
        } // If it isn't any of these cases, the player is on a mob they can't control and therefore is exempt

        // No, don't comment about the sqrt call.  It doesn't matter at all on modern CPU's.
        double offset = player.predictedVelocity.vector.distance(player.actualMovement);
        offset = player.uncertaintyHandler.reduceOffset(offset);

        // If the player is trying to riptide
        // But the server has rejected this movement
        // And there isn't water nearby (tries to solve most vanilla issues with this desync)
        //
        // Set back the player to disallow them to use riptide anywhere, even outside rain or water
        if (player.tryingToRiptide != player.compensatedRiptide.getCanRiptide() &&
                player.predictedVelocity.isTrident() &&
                // Don't let player do this too often as otherwise it could allow players to spam riptide
                (player.riptideSpinAttackTicks < 0 && !player.compensatedWorld.containsWater(GetBoundingBox.getCollisionBoxForPlayer(player, player.lastX, player.lastY, player.lastZ).expand(0.3, 0.3, 0.3)))) {
            player.getSetbackTeleportUtil().executeForceResync();
        }

        // This status gets reset on teleports
        //
        // Prevent desync by only removing offset when we are both blocking offsets AND
        // we have a pending setback with a transaction greater than ours
        SetBackData setbackData = player.getSetbackTeleportUtil().getRequiredSetBack();

        if (player.getSetbackTeleportUtil().blockOffsets && setbackData != null && setbackData.getTrans() + 1 > data.lastTransaction)
            offset = 0;

        // Don't check players who are offline
        if (!player.bukkitPlayer.isOnline()) return;
        // Don't check players who just switched worlds
        if (player.playerWorld != player.bukkitPlayer.getWorld()) return;

        if (wasChecked) {
            // We shouldn't attempt to send this prediction analysis into checks if we didn't predict anything
            player.checkManager.onPredictionFinish(new PredictionComplete(offset, data));
        } else {
            // The player wasn't checked, explosion and knockback status unknown
            player.checkManager.getExplosionHandler().forceExempt();
            player.checkManager.getKnockbackHandler().forceExempt();
        }

        player.riptideSpinAttackTicks--;
        if (player.predictedVelocity.isTrident())
            player.riptideSpinAttackTicks = 20;

        player.uncertaintyHandler.lastLastMovementWasZeroPointZeroThree = player.uncertaintyHandler.lastMovementWasZeroPointZeroThree;
        player.uncertaintyHandler.lastMovementWasZeroPointZeroThree = player.uncertaintyHandler.countsAsZeroPointZeroThree(player.predictedVelocity);
        player.uncertaintyHandler.lastLastPacketWasGroundPacket = player.uncertaintyHandler.lastPacketWasGroundPacket;
        player.uncertaintyHandler.lastPacketWasGroundPacket = player.uncertaintyHandler.wasLastOnGroundUncertain;
        player.uncertaintyHandler.wasZeroPointThreeVertically = player.uncertaintyHandler.gravityUncertainty != 0 || (player.uncertaintyHandler.lastMovementWasZeroPointZeroThree && player.uncertaintyHandler.controlsVerticalMovement());

        player.uncertaintyHandler.lastMetadataDesync--;

        if (player.playerVehicle instanceof PacketEntityRideable) {
            PacketEntityRideable rideable = (PacketEntityRideable) player.playerVehicle;
            rideable.entityPositions.clear();
            rideable.entityPositions.add(rideable.position);
        }

        player.lastX = player.x;
        player.lastY = player.y;
        player.lastZ = player.z;
        player.lastXRot = player.xRot;
        player.lastYRot = player.yRot;
        player.lastOnGround = player.onGround;

        player.vehicleData.vehicleForward = (float) Math.min(0.98, Math.max(-0.98, data.vehicleForward));
        player.vehicleData.vehicleHorizontal = (float) Math.min(0.98, Math.max(-0.98, data.vehicleHorizontal));
        player.vehicleData.horseJump = data.horseJump;

        player.checkManager.getKnockbackHandler().handlePlayerKb(offset);
        player.checkManager.getExplosionHandler().handlePlayerExplosion(offset);
        player.trigHandler.setOffset(oldClientVel, offset);
        player.compensatedRiptide.handleRemoveRiptide();
    }

    /**
     * Computes the movement from the riptide, and then uses it to determine whether the player
     * was more likely to be on or off of the ground when they started to riptide
     * <p>
     * A player on ground when riptiding will move upwards by 1.2f
     * We don't know whether the player was on the ground, however, which is why
     * we must attempt to guess here
     * <p>
     * Very reliable.
     *
     * @param pushingMovement The collision result when trying to move the player upwards by 1.2f
     * @return Whether it is more likely that this player was on the ground the tick they riptided
     */
    private boolean likelyGroundRiptide(Vector pushingMovement) {
        // Y velocity gets reset if the player collides vertically
        double riptideYResult = Riptide.getRiptideVelocity(player).getY();

        double riptideDiffToBase = Math.abs(player.actualMovement.getY() - riptideYResult);
        double riptideDiffToGround = Math.abs(player.actualMovement.getY() - riptideYResult - pushingMovement.getY());

        // If the player was very likely to have used riptide on the ground
        // (Patches issues with slime and other desync's)
        return riptideDiffToGround < riptideDiffToBase;
    }
}
