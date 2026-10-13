package com.desastres;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public final class DisasterManager {
    private static final List<Active> ACTIVE = new ArrayList<>();
    private static final List<Active> PENDING = new ArrayList<>();

    private static final int TSU_W = 15, TSU_H = 8, TSU_EVERY = 2, TSU_STEPS = 150, TSU_LIFE = 100;
    private static final int MEGA_W = 45, MEGA_H = 24, MEGA_EVERY = 2, MEGA_SLICES = 2, MEGA_STEPS = 260, MEGA_LIFE = 200;
    private static final int VOLC_H = 36;
    private static final double VOLC_R = 22.0;

    private static final int WATER_FLAGS = 2 | 128;

    private DisasterManager() {}

    public static void init() {
        ServerTickEvents.END_WORLD_TICK.register(DisasterManager::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.clear();
            PENDING.clear();
        });
    }

    public static void start(ServerWorld world, BlockPos pos, DisasterType type) {
        Active a = new Active(world, pos, type);
        PENDING.add(a);
        a.broadcast("⚠ " + type.nome + " iniciado!");
    }

    private static void tick(ServerWorld world) {
        if (!PENDING.isEmpty()) {
            ACTIVE.addAll(PENDING);
            PENDING.clear();
        }
        Iterator<Active> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Active a = it.next();
            if (a.world != world) continue;
            try {
                a.tick();
            } catch (Exception e) {
                e.printStackTrace();
                a.age = a.duration;
            }
            if (a.age >= a.duration) {
                a.finish();
                it.remove();
            }
        }
    }

    private static final class Meteor {
        FallingBlockEntity entity;
        double x, y, z;
        float power;
        boolean lava;
    }

    private static final class Active {
        final ServerWorld world;
        final BlockPos center;
        final DisasterType type;
        final Random rnd;
        int age = 0;
        int duration = 2400;

        double tx, tz, tdir;
        int dirX = 1, dirZ = 0, step = 0;
        final ArrayDeque<long[]> expiring = new ArrayDeque<>();
        final List<double[]> cracks = new ArrayList<>();
        int baseY;
        final List<Meteor> meteors = new ArrayList<>();

        Active(ServerWorld world, BlockPos center, DisasterType type) {
            this.world = world;
            this.center = center;
            this.type = type;
            this.rnd = world.getRandom();
            this.tx = center.getX() + 0.5;
            this.tz = center.getZ() + 0.5;
            this.tdir = rnd.nextDouble() * Math.PI * 2;
            switch (type) {
                case TSUNAMI -> {
                    pickDirection();
                    duration = TSU_STEPS * TSU_EVERY + TSU_LIFE + 20;
                }
                case MEGATSUNAMI -> {
                    pickDirection();
                    duration = ((MEGA_STEPS + MEGA_SLICES - 1) / MEGA_SLICES) * MEGA_EVERY + MEGA_LIFE + 20;
                }
                case VULCAO -> {
                    baseY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
                    duration = VOLC_H * 3 + 1500;
                }
                case TERREMOTO -> duration = 700;
                case MEGATERREMOTO -> duration = 1500;
                case TEMPESTADE_FOGO -> duration = 2000;
                default -> duration = 2400;
            }
        }

        void broadcast(String msg) {
            for (ServerPlayerEntity p : world.getPlayers()) {
                p.sendMessage(Text.literal(msg), true);
            }
        }

        boolean loaded(int x, int z) {
            return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
        }

        int groundY(int x, int z) {
            return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        }

        List<Entity> nearby(double x, double y, double z, double r, double h) {
            return world.getOtherEntities(null, new Box(x - r, y - h, z - r, x + r, y + h, z + r),
                    e -> !e.isSpectator() && !(e instanceof PlayerEntity p && p.getAbilities().flying));
        }

        void push(Entity e, double vx, double vy, double vz, double max) {
            Vec3d v = e.getVelocity().add(vx, vy, vz);
            double sp = v.length();
            if (sp > max) v = v.multiply(max / sp);
            e.setVelocity(v);
            e.velocityModified = true;
        }

        boolean weak(BlockState st, BlockPos p, float maxHardness) {
            if (st.isAir() || !st.getFluidState().isEmpty() || st.hasBlockEntity()) return false;
            float h = st.getHardness(world, p);
            return h >= 0 && h <= maxHardness;
        }

        static boolean natural(BlockState s) {
            return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND) || s.isIn(BlockTags.BASE_STONE_OVERWORLD)
                    || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY) || s.isOf(Blocks.SNOW_BLOCK);
        }

        int terrain(int x, int z) {
            int top = groundY(x, z);
            int min = Math.max(world.getBottomY(), top - 64);
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int y = top - 1; y >= min; y--) {
                m.set(x, y, z);
                if (natural(world.getBlockState(m))) return y;
            }
            return top - 1;
        }

        void flingBlock(BlockPos p, double vx, double vy, double vz) {
            BlockState st = world.getBlockState(p);
            FallingBlockEntity f = FallingBlockEntity.spawnFromBlock(world, p, st);
            f.setDestroyedOnLanding();
            f.setHurtEntities(2.0f, 20);
            f.setVelocity(vx, vy, vz);
            f.velocityModified = true;
        }

        void launch(BlockPos p, double vx, double vy, double vz, float power, boolean lava) {
            FallingBlockEntity f = FallingBlockEntity.spawnFromBlock(world, p, Blocks.MAGMA_BLOCK.getDefaultState());
            f.setDestroyedOnLanding();
            f.setHurtEntities(4.0f, 40);
            f.setVelocity(vx, vy, vz);
            f.velocityModified = true;
            Meteor m = new Meteor();
            m.entity = f;
            m.x = f.getX();
            m.y = f.getY();
            m.z = f.getZ();
            m.power = power;
            m.lava = lava;
            meteors.add(m);
        }

        void spawnMeteor(double x, double z, float power) {
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            if (!loaded(ix, iz)) return;
            int y = Math.min(groundY(ix, iz) + 70, world.getTopY() - 2);
            launch(new BlockPos(ix, y, iz), (rnd.nextDouble() - 0.5) * 0.6, -1.2, (rnd.nextDouble() - 0.5) * 0.6, power, false);
        }

        void tickMeteors() {
            Iterator<Meteor> it = meteors.iterator();
            while (it.hasNext()) {
                Meteor m = it.next();
                if (m.entity.isRemoved()) {
                    if (m.lava) {
                        BlockPos lp = new BlockPos((int) Math.floor(m.x), (int) Math.floor(m.y), (int) Math.floor(m.z));
                        BlockState cur = world.getBlockState(lp);
                        if (cur.isAir() || cur.isReplaceable()) {
                            world.setBlockState(lp, Blocks.LAVA.getDefaultState(), 3);
                        }
                    } else if (m.power > 0) {
                        world.createExplosion(null, m.x, m.y, m.z, m.power, true, World.ExplosionSourceType.TNT);
                    }
                    it.remove();
                } else {
                    m.x = m.entity.getX();
                    m.y = m.entity.getY();
                    m.z = m.entity.getZ();
                    world.spawnParticles(ParticleTypes.FLAME, m.x, m.y + 0.5, m.z, 4, 0.3, 0.3, 0.3, 0.02);
                    world.spawnParticles(ParticleTypes.LARGE_SMOKE, m.x, m.y + 0.5, m.z, 2, 0.3, 0.3, 0.3, 0.02);
                }
            }
        }

        double[] target(double spread, double fallbackR) {
            List<ServerPlayerEntity> ps = new ArrayList<>();
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (p.squaredDistanceTo(center.getX(), p.getY(), center.getZ()) < 200.0 * 200.0) ps.add(p);
            }
            if (!ps.isEmpty() && rnd.nextInt(3) != 0) {
                ServerPlayerEntity p = ps.get(rnd.nextInt(ps.size()));
                return new double[]{p.getX() + (rnd.nextDouble() - 0.5) * 2 * spread,
                        p.getZ() + (rnd.nextDouble() - 0.5) * 2 * spread};
            }
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * fallbackR;
            return new double[]{center.getX() + 0.5 + Math.cos(a) * r, center.getZ() + 0.5 + Math.sin(a) * r};
        }

        void pickDirection() {
            PlayerEntity p = world.getClosestPlayer(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 256, false);
            if (p != null) {
                double dx = p.getX() - (center.getX() + 0.5), dz = p.getZ() - (center.getZ() + 0.5);
                if (Math.abs(dx) >= Math.abs(dz)) {
                    dirX = dx >= 0 ? 1 : -1;
                    dirZ = 0;
                } else {
                    dirX = 0;
                    dirZ = dz >= 0 ? 1 : -1;
                }
            } else {
                int d = rnd.nextInt(4);
                dirX = d == 0 ? 1 : d == 1 ? -1 : 0;
                dirZ = d == 2 ? 1 : d == 3 ? -1 : 0;
            }
        }

        void tick() {
            switch (type) {
                case FURACAO -> hurricane();
                case TORNADO -> tornado();
                case TSUNAMI -> wave(TSU_W, TSU_H, TSU_EVERY, 1, TSU_STEPS, TSU_LIFE, 1.0f, 0.45);
                case MEGATSUNAMI -> wave(MEGA_W, MEGA_H, MEGA_EVERY, MEGA_SLICES, MEGA_STEPS, MEGA_LIFE, 3.5f, 0.9);
                case EXTINCAO -> extinction();
                case VULCAO -> volcano();
                case TEMPESTADE_RAIOS -> thunderstorm();
                case TEMPESTADE_FOGO -> fireStorm();
                case TERREMOTO -> quake(40, 0.25, 1, 6, 4, false, 2.5f);
                case MEGATERREMOTO -> quake(120, 0.6, 3, 24, 8, true, 6.0f);
            }
            tickMeteors();
            age++;
        }

        void finish() {
            for (long[] e : expiring) {
                BlockPos p = BlockPos.fromLong(e[1]);
                if (loaded(p.getX(), p.getZ()) && world.getBlockState(p).isOf(Blocks.WATER)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                }
            }
            expiring.clear();
            for (Meteor m : meteors) m.entity.discard();
            meteors.clear();
            broadcast("O desastre acabou: " + type.nome);
        }

        void hurricane() {
            double cx = center.getX() + 0.5, cz = center.getZ() + 0.5, R = 72;
            if (age % 100 == 0) world.setWeather(0, duration - age + 200, true, true);

            if (age % 2 == 0) {
                for (Entity e : nearby(cx, center.getY(), cz, R, 64)) {
                    double dx = e.getX() - cx, dz = e.getZ() - cz;
                    double d = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
                    if (d > R || d < 8) continue;
                    double s = 0.12 * (1.0 - 0.5 * d / R);
                    push(e, (-dz / d) * s - (dx / d) * 0.04, e.isOnGround() ? 0.0 : 0.012,
                            (dx / d) * s - (dz / d) * 0.04, 1.8);
                }
            }

            if (age % 3 == 0) {
                for (int i = 0; i < 10; i++) {
                    double ang = rnd.nextDouble() * Math.PI * 2, r = 8 + rnd.nextDouble() * (R - 8);
                    int x = (int) Math.floor(cx + Math.cos(ang) * r), z = (int) Math.floor(cz + Math.sin(ang) * r);
                    if (!loaded(x, z)) continue;
                    BlockPos p = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1, z);
                    if (weak(world.getBlockState(p), p, 2.0f)) {
                        double rx = x + 0.5 - cx, rz = z + 0.5 - cz, d = Math.max(1, Math.sqrt(rx * rx + rz * rz));
                        flingBlock(p, (-rz / d) * 0.9, 0.5 + rnd.nextDouble() * 0.4, (rx / d) * 0.9);
                    }
                }
            }

            for (ServerPlayerEntity pl : world.getPlayers()) {
                if (pl.squaredDistanceTo(cx, pl.getY(), cz) < (R + 24) * (R + 24)) {
                    world.spawnParticles(ParticleTypes.CLOUD, pl.getX(), pl.getY() + 4, pl.getZ(), 4, 8, 4, 8, 0.05);
                }
            }
        }

        void tornado() {
            if (age % 100 == 0) world.setWeather(0, duration - age + 200, true, true);
            tdir += (rnd.nextDouble() - 0.5) * 0.18;
            tx += Math.cos(tdir) * 0.35;
            tz += Math.sin(tdir) * 0.35;
            int ix = (int) Math.floor(tx), iz = (int) Math.floor(tz);
            if (!loaded(ix, iz)) {
                age = duration;
                return;
            }
            int gy = groundY(ix, iz);

            for (int h = 0; h < 48; h += 2) {
                double r = 1.5 + h * 0.2, a = age * 0.6 + h * 0.35;
                double px = tx + Math.cos(a) * r, pz = tz + Math.sin(a) * r;
                world.spawnParticles(ParticleTypes.CLOUD, px, gy + h, pz, 2, 0.6, 0.6, 0.6, 0.02);
                if (h < 20) world.spawnParticles(ParticleTypes.LARGE_SMOKE, px, gy + h, pz, 1, 0.3, 0.3, 0.3, 0.02);
            }

            for (Entity e : nearby(tx, gy + 20, tz, 14, 32)) {
                double dx = tx - e.getX(), dz = tz - e.getZ();
                double d = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
                if (d > 14) continue;
                double f = 1.0 - d / 14.0;
                push(e, dx / d * 0.10 * f + dz / d * 0.14 * f, 0.06 + 0.10 * f,
                        dz / d * 0.10 * f - dx / d * 0.14 * f, 1.4);
            }

            if (age % 2 == 0) {
                for (int i = 0; i < 4; i++) {
                    int x = ix + rnd.nextInt(15) - 7, z = iz + rnd.nextInt(15) - 7;
                    if (!loaded(x, z)) continue;
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1 - rnd.nextInt(3);
                    BlockPos p = new BlockPos(x, y, z);
                    if (weak(world.getBlockState(p), p, 2.5f)) {
                        double rx = x + 0.5 - tx, rz = z + 0.5 - tz, d = Math.max(1, Math.sqrt(rx * rx + rz * rz));
                        flingBlock(p, (-rz / d) * 0.8 - rx / d * 0.2, 0.9 + rnd.nextDouble() * 0.5,
                                (rx / d) * 0.8 - rz / d * 0.2);
                    }
                }
            }
        }

        void wave(int w, int h, int every, int slices, int maxSteps, int life, float breakH, double pushStr) {
            if (age % every == 0 && step < maxSteps) {
                for (int i = 0; i < slices && step < maxSteps; i++) {
                    step++;
                    slice(step, w, h, life, breakH);
                }
            }
            while (!expiring.isEmpty() && expiring.peekFirst()[0] <= age) {
                long[] e = expiring.pollFirst();
                BlockPos p = BlockPos.fromLong(e[1]);
                if (loaded(p.getX(), p.getZ()) && world.getBlockState(p).isOf(Blocks.WATER)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                }
            }
            if (age % 2 == 0 && step > 0 && step < maxSteps + 20) {
                double fx = center.getX() + 0.5 + dirX * step, fz = center.getZ() + 0.5 + dirZ * step;
                for (Entity e : nearby(fx, center.getY(), fz, w + 2, 48)) {
                    double along = (e.getX() - fx) * dirX + (e.getZ() - fz) * dirZ;
                    if (along < -14 || along > 6) continue;
                    push(e, dirX * pushStr, 0.08, dirZ * pushStr, 2.0);
                }
            }
        }

        void slice(int s, int w, int h, int life, float breakH) {
            int bx = center.getX() + dirX * s, bz = center.getZ() + dirZ * s;
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int k = -w; k <= w; k++) {
                int x = bx - dirZ * k, z = bz + dirX * k;
                if (!loaded(x, z)) continue;
                int base = Math.max(terrain(x, z) + 1, world.getSeaLevel());
                for (int y = base; y < base + h && y < world.getTopY(); y++) {
                    m.set(x, y, z);
                    BlockState st = world.getBlockState(m);
                    if (!st.getFluidState().isEmpty()) continue;
                    if (st.isAir() || st.isReplaceable() || weak(st, m, breakH)) {
                        world.setBlockState(m, Blocks.WATER.getDefaultState(), WATER_FLAGS);
                        expiring.add(new long[]{age + life, m.asLong()});
                    }
                }
            }
        }

        void extinction() {
            double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
            if (age == 0) world.setWeather(0, duration, true, true);
            double R = Math.min(200, 24 + age * 0.08);
            double Rv = Math.min(R, 100);

            if (age % 5 == 0) {
                Box box = new Box(cx - R, world.getBottomY(), cz - R, cx + R, world.getTopY(), cz + R);
                for (Entity e : world.getOtherEntities(null, box, x -> x instanceof LivingEntity)) {
                    LivingEntity le = (LivingEntity) e;
                    if (le instanceof PlayerEntity || le instanceof ArmorStandEntity
                            || le.hasCustomName() || le.getMaxHealth() >= 150) continue;
                    world.spawnParticles(ParticleTypes.SOUL, le.getX(), le.getY() + 0.5, le.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
                    le.kill();
                }
            }

            for (int i = 0; i < 150; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * Rv;
                int x = (int) Math.floor(cx + Math.cos(a) * r), z = (int) Math.floor(cz + Math.sin(a) * r);
                if (!loaded(x, z)) continue;
                int tNL = groundY(x, z);
                int tM = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos plant = new BlockPos(x, tNL, z);
                if (world.getBlockState(plant).getBlock() instanceof PlantBlock) world.removeBlock(plant, false);
                BlockPos leaf = new BlockPos(x, tM - 1, z);
                if (world.getBlockState(leaf).getBlock() instanceof LeavesBlock) world.removeBlock(leaf, false);
                BlockPos grass = new BlockPos(x, tNL - 1, z);
                if (world.getBlockState(grass).isOf(Blocks.GRASS_BLOCK)) {
                    world.setBlockState(grass, Blocks.COARSE_DIRT.getDefaultState(), 3);
                }
            }

            if (age % 40 == 20) {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * Rv;
                spawnMeteor(cx + Math.cos(a) * r, cz + Math.sin(a) * r, 5.0f);
            }
            if (age % 15 == 0) {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * Rv;
                spawnMeteor(cx + Math.cos(a) * r, cz + Math.sin(a) * r, 3.0f);
            }
        }

        BlockState volcanoBlock() {
            int r = rnd.nextInt(100);
            if (r < 7) return Blocks.MAGMA_BLOCK.getDefaultState();
            if (r < 45) return Blocks.BASALT.getDefaultState();
            if (r < 75) return Blocks.BLACKSTONE.getDefaultState();
            if (r < 90) return Blocks.TUFF.getDefaultState();
            return Blocks.COBBLED_DEEPSLATE.getDefaultState();
        }

        void volcano() {
            int cx = center.getX(), cz = center.getZ();
            int buildTicks = VOLC_H * 3;
            int topY = baseY - 2 + VOLC_H;

            if (age < buildTicks && age % 3 == 0) {
                int l = age / 3;
                int y = baseY - 2 + l;
                double rad = VOLC_R * (1.0 - (double) l / VOLC_H) + 2.5;
                int ri = (int) Math.ceil(rad);
                boolean crater = l >= VOLC_H - 4;
                for (int dx = -ri; dx <= ri; dx++) {
                    for (int dz = -ri; dz <= ri; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d > rad || !loaded(cx + dx, cz + dz)) continue;
                        BlockState st;
                        if (crater && d <= 2.2) {
                            st = (l == VOLC_H - 4) ? Blocks.LAVA.getDefaultState() : Blocks.AIR.getDefaultState();
                        } else {
                            st = volcanoBlock();
                        }
                        BlockPos p = new BlockPos(cx + dx, y, cz + dz);
                        BlockState old = world.getBlockState(p);
                        if (old.getHardness(world, p) < 0 || old.hasBlockEntity()) continue;
                        world.setBlockState(p, st, 2);
                        if (l < 2) {
                            for (int k = 1; k <= 12; k++) {
                                BlockPos q = p.down(k);
                                BlockState qs = world.getBlockState(q);
                                if (qs.isAir() || qs.isReplaceable() || !qs.getFluidState().isEmpty()) {
                                    world.setBlockState(q, Blocks.BASALT.getDefaultState(), 2);
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                }
                world.playSound(null, cx + 0.5, y, cz + 0.5, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0f, 0.5f);
            }

            if (age >= buildTicks) {
                int e = age - buildTicks;
                if (e < 1200) {
                    if (e % 3 == 0) {
                        for (int i = 0; i < 3; i++) {
                            double a = rnd.nextDouble() * Math.PI * 2, sp = 0.3 + rnd.nextDouble() * 0.9;
                            boolean lava = rnd.nextInt(3) == 0;
                            launch(new BlockPos(cx, topY + 1, cz), Math.cos(a) * sp, 1.6 + rnd.nextDouble() * 1.4,
                                    Math.sin(a) * sp, lava ? 0f : 2.0f, lava);
                        }
                    }
                    if (e % 30 == 0) {
                        double a = rnd.nextDouble() * Math.PI * 2;
                        BlockPos rim = new BlockPos(cx + (int) Math.round(2.6 * Math.cos(a)), topY - 1,
                                cz + (int) Math.round(2.6 * Math.sin(a)));
                        world.setBlockState(rim, Blocks.LAVA.getDefaultState(), 3);
                    }
                    if (e % 20 == 0) {
                        world.playSound(null, cx + 0.5, topY, cz + 0.5, SoundEvents.ENTITY_GENERIC_EXPLODE,
                                SoundCategory.BLOCKS, 6.0f, 0.4f);
                    }
                    world.spawnParticles(ParticleTypes.LARGE_SMOKE, cx + 0.5, topY + 2, cz + 0.5, 12, 2, 4, 2, 0.1);
                    world.spawnParticles(ParticleTypes.LAVA, cx + 0.5, topY + 1, cz + 0.5, 6, 1.5, 1, 1.5, 0.5);
                }
            }
        }

        void thunderstorm() {
            double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
            if (age % 100 == 0) world.setWeather(0, duration - age + 200, true, true);
            if (age % 3 == 0) {
                double[] t = target(24, 80);
                strike(t[0], t[1]);
                if (rnd.nextInt(3) == 0) {
                    double[] t2 = target(24, 80);
                    strike(t2[0], t2[1]);
                }
            }
            if (age % 2 == 0) {
                double ang = age * 0.002;
                for (Entity e : nearby(cx, center.getY(), cz, 80, 64)) {
                    push(e, Math.cos(ang) * 0.05, 0, Math.sin(ang) * 0.05, 0.9);
                }
            }
        }

        void strike(double x, double z) {
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            if (!loaded(ix, iz)) return;
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
            if (bolt == null) return;
            bolt.setPosition(x, groundY(ix, iz), z);
            world.spawnEntity(bolt);
        }

        void fireStorm() {
            double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
            if (age % 100 == 0) world.setWeather(duration - age + 200, 0, false, false);

            if (age % 4 == 0) {
                double[] t = target(20, 70);
                spawnMeteor(t[0], t[1], 2.5f);
                if (rnd.nextBoolean()) {
                    double[] t2 = target(20, 70);
                    spawnMeteor(t2[0], t2[1], 2.5f);
                }
            }

            if (age % 20 == 0) {
                for (Entity e : nearby(cx, center.getY(), cz, 90, 100)) {
                    if (e instanceof LivingEntity && world.isSkyVisible(e.getBlockPos())) e.setOnFireFor(4);
                }
            }

            for (int i = 0; i < 8; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 70;
                int x = (int) Math.floor(cx + Math.cos(a) * r), z = (int) Math.floor(cz + Math.sin(a) * r);
                if (!loaded(x, z)) continue;
                BlockPos p = new BlockPos(x, groundY(x, z), z);
                if (world.getBlockState(p).isAir()) {
                    BlockState fire = AbstractFireBlock.getState(world, p);
                    if (fire.canPlaceAt(world, p)) world.setBlockState(p, fire, 3);
                }
            }
        }

        void quake(int radius, double power, int w, int depth, int count, boolean lava, float breakH) {
            double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
            if (age == 0) {
                for (int i = 0; i < count; i++) {
                    cracks.add(new double[]{cx, cz, rnd.nextDouble() * Math.PI * 2, 0, radius * (0.5 + rnd.nextDouble() * 0.5)});
                }
            }

            for (double[] c : cracks) {
                if (c[3] >= c[4]) continue;
                c[2] += (rnd.nextDouble() - 0.5) * 0.35;
                c[0] += Math.cos(c[2]);
                c[1] += Math.sin(c[2]);
                c[3] += 1;
                carve((int) Math.floor(c[0]), (int) Math.floor(c[1]), w, depth, lava);
            }

            if (age % 2 == 0) {
                for (Entity e : nearby(cx, center.getY(), cz, radius, 40)) {
                    if (e.isOnGround()) {
                        push(e, (rnd.nextDouble() - 0.5) * power, rnd.nextDouble() * power * 0.5,
                                (rnd.nextDouble() - 0.5) * power, 1.5);
                    }
                }
            }

            if (age % 20 == 0) {
                for (ServerPlayerEntity p : world.getPlayers()) {
                    if (p.squaredDistanceTo(cx, p.getY(), cz) < (radius * 1.2) * (radius * 1.2)) {
                        world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE,
                                SoundCategory.BLOCKS, 1.5f, 0.4f);
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 140, 0, false, false));
                    }
                }
            }

            int tries = radius / 3 + 4;
            for (int i = 0; i < tries; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = radius * rnd.nextDouble() * rnd.nextDouble();
                int x = (int) Math.floor(cx + Math.cos(a) * r), z = (int) Math.floor(cz + Math.sin(a) * r);
                if (!loaded(x, z)) continue;
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
                BlockPos p = new BlockPos(x, top - rnd.nextInt(10), z);
                if (weak(world.getBlockState(p), p, breakH)) world.breakBlock(p, false);
            }
        }

        void carve(int cx0, int cz0, int w, int depth, boolean lava) {
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int dx = -w; dx <= w; dx++) {
                for (int dz = -w; dz <= w; dz++) {
                    if (dx * dx + dz * dz > w * w) continue;
                    int x = cx0 + dx, z = cz0 + dz;
                    if (!loaded(x, z)) continue;
                    int top = groundY(x, z);
                    m.set(x, top, z);
                    if (world.getBlockState(m).getBlock() instanceof PlantBlock) world.removeBlock(m, false);
                    for (int dy = 0; dy < depth; dy++) {
                        int y = top - 1 - dy;
                        if (y <= world.getBottomY() + 1) break;
                        m.set(x, y, z);
                        BlockState st = world.getBlockState(m);
                        if (st.isAir()) continue;
                        if (st.getHardness(world, m) < 0 || st.hasBlockEntity()) break;
                        world.setBlockState(m, Blocks.AIR.getDefaultState(), 3);
                    }
                    if (lava) {
                        int y = top - 1 - depth;
                        if (y > world.getBottomY() + 1) {
                            m.set(x, y, z);
                            BlockState st = world.getBlockState(m);
                            if (st.getHardness(world, m) >= 0 && !st.hasBlockEntity()) {
                                world.setBlockState(m, Blocks.LAVA.getDefaultState(), 3);
                            }
                        }
                    }
                }
            }
        }
    }
                }
