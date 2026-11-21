package com.baggute.cheatclient;

import baritone.api.Settings;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.Waypoint;
import baritone.api.command.datatypes.IDatatypeContext;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.*;
import baritone.api.schematic.mask.shape.CylinderMask;
import baritone.api.utils.*;
import baritone.cache.CachedChunk;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import baritone.utils.pathing.PathingBlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.stream.Stream;

/** Executes repaired vendor helpers with real vanilla blocks and registries. */
public final class BaritoneRepairChecks {
    public static void run() throws Exception {
        equalityAndGoals(); schematics(); settingsAndArguments(); cachedHeights();
        System.out.println("PASS: Baritone mixed-position/waypoint hashes, distant goals, preserved block states, small cylinders, nested reset, empty collections, quoted/class-peek arguments and cached world heights");
    }
    private static void equalityAndGoals() {
        BlockPos ordinary = new BlockPos(4, -20, 6);
        BetterBlockPos better = new BetterBlockPos(ordinary);
        require(better.equals(ordinary) && ordinary.equals(better) && better.hashCode() == ordinary.hashCode(), "mixed coordinate equality/hash");
        require(!better.equals("wrong") && !better.equals(null), "unrelated coordinates are unequal");
        require(new HashSet<>(List.of(ordinary)).contains(better), "mixed set membership");
        Waypoint first = new Waypoint("base", IWaypoint.Tag.USER, better, 1);
        Waypoint later = new Waypoint("base", IWaypoint.Tag.USER, better, 2);
        require(first.equals(later) && new HashSet<>(List.of(first)).contains(later), "waypoint timestamps do not split identity");
        GoalNear near = new GoalNear(BlockPos.ZERO, 3);
        require(!near.isInGoal(50_000, 0, 0) && near.isInGoal(3, 0, 0) && near.heuristic(3, 0, 0) == 0, "large-distance near goal and arrival heuristic");
        GoalRunAway away = new GoalRunAway(20, BlockPos.ZERO);
        require(away.isInGoal(50_000, 0, 0) && !away.isInGoal(10, 0, 0), "run-away distance cannot overflow");
        away.hashCode(); require(away.heuristic() == 0 && !new GoalAxis().equals(null), "nullable goal and zero arrival threshold");
        BlockPos.MutableBlockPos origin = new BlockPos.MutableBlockPos(0, 0, 0);
        away = new GoalRunAway(20, origin); origin.set(200, 0, 0);
        require(!away.isInGoal(0, 0, 0), "origins are immutable snapshots");
        GoalRunAway reversed = new GoalRunAway(20, new BlockPos(1_000_000, 0, 0), BlockPos.ZERO);
        GoalRunAway forward = new GoalRunAway(20, BlockPos.ZERO, new BlockPos(1_000_000, 0, 0));
        for (int x : new int[]{0, 25, 500_000, 999_990, 1_000_100})
            require(reversed.isInGoal(x, 0, 0) == forward.isInGoal(x, 0, 0), "origin order preserves geometry");
    }
    private static void schematics() {
        BlockState horizontal = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState vertical = Blocks.OAK_LOG.defaultBlockState();
        FillSchematic fill = new FillSchematic(1, 1, 1, horizontal);
        require(fill.desiredState(0, 0, 0, vertical, List.of(horizontal)).equals(horizontal), "fill retains exact log axis");
        for (Direction.Axis axis : Direction.Axis.values()) {
            CylinderMask small = new CylinderMask(2, 2, 2, true, axis);
            for (int x=0; x<2; x++) for (int y=0; y<2; y++) for (int z=0; z<2; z++)
                require(small.partOfMask(x,y,z), "two-block cylinder has no zero radius");
            require(!small.partOfMask(-1,0,0) && !small.partOfMask(2,0,0), "cylinder respects selection bounds");
        }
        require(!new CylinderMask(0,2,2,true,Direction.Axis.Y).partOfMask(0,0,0), "empty selection stays empty");
        int[] resets = {0};
        FillSchematic probe = new FillSchematic(1,1,1,horizontal) { @Override public void reset() { resets[0]++; } };
        ReplaceSchematic replace = new ReplaceSchematic(probe, new BlockOptionalMetaLookup(new BlockOptionalMeta[0]));
        SubstituteSchematic wrapper = new SubstituteSchematic(MaskSchematic.create(replace, new CylinderMask(1,1,1,true,Direction.Axis.Y)), Map.of());
        require(!wrapper.inSchematic(0,0,0,vertical), "empty replace filter excludes the block");
        wrapper.reset(); require(resets[0] == 1, "reset reaches inner schematic through every decorator");
    }
    private static void settingsAndArguments() throws Exception {
        var constructor = Settings.class.getDeclaredConstructor(); constructor.setAccessible(true);
        Settings settings = constructor.newInstance();
        require(settings.acceptableThrowawayItems.value != settings.acceptableThrowawayItems.defaultValue, "mutable defaults do not alias live values");
        var defaults = new ArrayList<>(settings.acceptableThrowawayItems.defaultValue);
        settings.acceptableThrowawayItems.value.clear();
        require(settings.acceptableThrowawayItems.defaultValue.equals(defaults), "clearing value preserves defaults");
        require(SettingsUtil.modifiedSettings(settings).contains(settings.acceptableThrowawayItems), "empty changed list is persisted");
        require(SettingsUtil.settingToString(settings.acceptableThrowawayItems).endsWith(" "), "empty list has a parseable serialized record");
        SettingsUtil.parseAndApply(settings, "acceptablethrowawayitems", "");
        require(settings.acceptableThrowawayItems.value.isEmpty(), "empty collection parses");
        String command = "\"my house.schem\" C:\\maps\\house.schem escaped\\ name ''";
        var args = CommandArguments.from(command, false);
        require(args.size() == 4 && args.get(0).getValue().equals("my house.schem")
                && args.get(1).getValue().equals("C:\\maps\\house.schem")
                && args.get(2).getValue().equals("escaped name") && args.get(3).getValue().isEmpty(), "quoted and escaped paths tokenize once");
        require(args.get(0).getRawRest().equals(command), "raw spans retain quoting");
        require(CommandArguments.from("\"unfinished path", true).get(0).getValue().equals("unfinished path"), "open quote supports completion");
        ArgConsumer consumer = new ArgConsumer(null, CommandArguments.from("42", false));
        require(consumer.<Integer, IntegerType>peekDatatypeFor(IntegerType.class) == 42 && consumer.hasExactlyOne(), "class-based peek dispatches once without consumption");
        require(consumer.<Integer, IntegerType>peekDatatypeForOrDefault(IntegerType.class, -1) == 42 && consumer.hasExactlyOne(), "class-based default peek parses without consumption");
        require(consumer.<Integer, IntegerType>peekDatatypeForOrNull(IntegerType.class) == 42 && consumer.hasExactlyOne(), "class-based nullable peek parses without recursion");
    }
    public static final class IntegerType implements IDatatypeFor<Integer> {
        public Integer get(IDatatypeContext context) throws baritone.api.command.exception.CommandException { return Integer.parseInt(context.getConsumer().getString()); }
        public Stream<String> tabComplete(IDatatypeContext context) { return Stream.empty(); }
    }
    private static void cachedHeights() throws Exception {
        var dimension = MinecraftBootstrap.registries().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("minecraft:overworld"))).value();
        BitSet bits = new BitSet(CachedChunk.size(384));
        for (int y : new int[]{3,54,55,383}) {
            int at = CachedChunk.getPositionIndex(1,y,2);
            boolean[] type = PathingBlockType.SOLID.getBits(); bits.set(at,type[0]); bits.set(at+1,type[1]);
        }
        BlockState[] overview = new BlockState[256]; Arrays.fill(overview, Blocks.STONE.defaultBlockState());
        var constructor = CachedChunk.class.getDeclaredConstructor(int.class,int.class,int.class,BitSet.class,BlockState[].class,Map.class,long.class);
        constructor.setAccessible(true);
        CachedChunk chunk = constructor.newInstance(0,0,384,bits,overview,Map.of("minecraft:chest",List.of(new BlockPos(1,-10,2))),1L);
        require(chunk.getBlock(1,54,2,dimension,Level.OVERWORLD).is(Blocks.CHEST), "special block below negative-Y origin is exact");
        require(chunk.getBlock(1,3,2,dimension,Level.OVERWORLD).is(Blocks.OBSIDIAN), "bottom threshold uses relative Y");
        require(chunk.getBlock(1,383,2,dimension,Level.OVERWORLD).is(Blocks.STONE), "height map includes topmost cell");
        require(chunk.getBlock(1,384,2,dimension,Level.OVERWORLD).is(Blocks.AIR) && chunk.getBlock(1,-1,2,dimension,Level.OVERWORLD).is(Blocks.AIR), "out-of-height reads are bounded");
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
