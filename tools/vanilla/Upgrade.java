import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.ItemIdFix;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Upgrades block states and item names of older releases to the running (newest) release with Mojang's own
 * DataFixer - the same path worlds take when they are opened in a newer game. Run on the newest server classpath.
 *
 * Modes (tab-separated files):
 *   blocks  <in: dataVersion, state> <out: state>          upgrade block state strings
 *   items   <in: dataVersion, item>  <out: item>           upgrade item names
 *   legacy-blocks <out: legacyId, state>                    flatten pre-1.13 id<<4|meta block states
 *   legacy-items  <out: id, damage, item>                   flatten pre-1.13 numeric items
 *   canonical <out: id, state, solid, default>              block states of this release in network ID order
 */
public class Upgrade {
    /** Data version of 17w47a, the snapshot that flattened blocks and items. */
    private static final int FLATTENING = 1451;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int target = SharedConstants.getCurrentVersion().dataVersion().version();
        switch (args[0]) {
            case "blocks" -> map(args, line -> upgradeBlock(parseState(line[1]), Integer.parseInt(line[0]), target));
            case "items" -> map(args, line -> upgradeItem(line[1], Integer.parseInt(line[0]), target));
            case "legacy-blocks" -> legacyBlocks(Path.of(args[1]), target);
            case "legacy-items" -> legacyItems(Path.of(args[1]), target);
            case "canonical" -> canonical(Path.of(args[1]));
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }

    private interface LineMapper {
        String apply(String[] line);
    }

    private static void map(String[] args, LineMapper mapper) throws Exception {
        var output = new ArrayList<String>();
        for (var line : Files.readAllLines(Path.of(args[1]))) {
            if (!line.isBlank()) output.add(mapper.apply(line.split("\t")));
        }
        Files.write(Path.of(args[2]), output);
    }

    private static void legacyBlocks(Path output, int target) throws Exception {
        var lines = new ArrayList<String>();
        for (int id = 0; id < 4096; id++) {
            var tag = BlockStateData.getTag(id);
            @SuppressWarnings("unchecked")
            var upgraded = DataFixers.getDataFixer().update(References.BLOCK_STATE, (Dynamic<Tag>) (Dynamic<?>) convert(tag), FLATTENING, target);
            lines.add(id + "\t" + format(upgraded));
        }
        Files.write(output, lines);
    }

    private static void legacyItems(Path output, int target) throws Exception {
        var lines = new ArrayList<String>();
        for (int id = 0; id < 2300; id++) {
            var name = ItemIdFix.getItem(id);
            if (name == null || (id != 0 && name.equals("minecraft:air"))) continue;
            for (int damage = 0; damage < 16; damage++) {
                var flattened = ItemStackTheFlatteningFix.updateItem(name, damage);
                if (flattened == null) {
                    if (damage > 0) continue;
                    flattened = name;
                }
                lines.add(id + "\t" + damage + "\t" + upgradeItem(flattened, FLATTENING, target));
            }
        }
        Files.write(output, lines);
    }

    private static void canonical(Path output) throws Exception {
        var lines = new ArrayList<String>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            var properties = new TreeMap<String, String>();
            state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
            lines.add(Block.getId(state) + "\t" + format(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties)
                    + "\t" + (state.isSolid() ? 1 : 0) + "\t" + (state == state.getBlock().defaultBlockState() ? 1 : 0));
        }
        Files.write(output, lines);
    }

    private static String upgradeBlock(CompoundTag state, int from, int target) {
        var upgraded = DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<Tag>(NbtOps.INSTANCE, state), from, target);
        return format(upgraded);
    }

    private static String upgradeItem(String item, int from, int target) {
        var upgraded = DataFixers.getDataFixer().update(References.ITEM_NAME, new Dynamic<Tag>(NbtOps.INSTANCE, StringTag.valueOf(item)), from, target);
        return upgraded.asString(item);
    }

    /** {@code minecraft:name[a=b,c=d]} to {Name, Properties}. */
    private static CompoundTag parseState(String state) {
        var tag = new CompoundTag();
        int bracket = state.indexOf('[');
        tag.putString("Name", bracket < 0 ? state : state.substring(0, bracket));
        if (bracket >= 0) {
            var properties = new CompoundTag();
            for (var pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                var parts = pair.split("=", 2);
                properties.putString(parts[0], parts[1]);
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    private static Dynamic<?> convert(Dynamic<?> dynamic) {
        return new Dynamic<>(NbtOps.INSTANCE, dynamic.convert(NbtOps.INSTANCE).getValue());
    }

    /** Block states are {Name, Properties} in old schemas and {id, properties} since 26.x. */
    private static String format(Dynamic<?> state) {
        var properties = new TreeMap<String, String>();
        var propertyTag = state.get("Properties").result().isPresent() ? state.get("Properties") : state.get("properties");
        propertyTag.asMap(key -> key.asString(""), value -> value.asString("")).forEach(properties::put);
        var name = state.get("Name").asString().result().orElseGet(() -> state.get("id").asString("minecraft:air"));
        return format(name, properties);
    }

    private static String format(String name, TreeMap<String, String> properties) {
        if (properties.isEmpty()) return name;
        List<String> pairs = new ArrayList<>();
        properties.forEach((key, value) -> pairs.add(key + "=" + value));
        return name + "[" + String.join(",", pairs) + "]";
    }
}
