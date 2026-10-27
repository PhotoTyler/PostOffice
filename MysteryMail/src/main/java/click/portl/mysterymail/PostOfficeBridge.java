package click.portl.mysterymail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Talks to an unmodified PostOffice (1.8.x) through its internals, so the PostOffice jar on the
 * server never has to change. Mystery mail is posted at the drop-off nearest the player's mailbox
 * (or at the mailbox itself if there's no drop-off in that world), so the Postman collects it and
 * walks it over like any other post.
 */
final class PostOfficeBridge {
    /** Shown as the "sender" in PostOffice's queue. Not a real player, so nobody gets "returned"/"stolen" messages. */
    private static final UUID MYSTERY_SENDER = UUID.nameUUIDFromBytes("MysteryMail".getBytes(StandardCharsets.UTF_8));

    private final Object blocks;          // PostBlocks
    private final Object post;            // PostService
    private final Map<?, ?> allBlocks;    // DataStore.blocks: id -> PostBlock
    private final Method mailboxesOf, sealedLetter, parcel, encode, postDelivery, isMailbox;
    private final Field fId, fType, fWorld, fX, fY, fZ;
    private final Constructor<?> delivery;

    PostOfficeBridge(Plugin postOffice) throws ReflectiveOperationException {
        ClassLoader cl = postOffice.getClass().getClassLoader();
        String pkg = "click.portl.postoffice.";
        Class<?> pluginCls = postOffice.getClass();
        blocks = open(pluginCls.getDeclaredMethod("blocks")).invoke(postOffice);
        post = open(pluginCls.getDeclaredMethod("post")).invoke(postOffice);
        Object store = open(pluginCls.getDeclaredMethod("store")).invoke(postOffice);
        allBlocks = (Map<?, ?>) open(store.getClass().getDeclaredField("blocks")).get(store);

        mailboxesOf = open(blocks.getClass().getDeclaredMethod("mailboxesOf", UUID.class));
        Class<?> mailItems = Class.forName(pkg + "MailItems", true, cl);
        sealedLetter = open(mailItems.getDeclaredMethod("sealedLetter", List.class, String.class, ItemStack.class));
        parcel = open(mailItems.getDeclaredMethod("parcel", List.class, String.class));
        encode = open(Class.forName(pkg + "ItemCodec", true, cl).getDeclaredMethod("encode", ItemStack.class));

        Class<?> postBlock = Class.forName(pkg + "PostBlock", true, cl);
        fId = open(postBlock.getDeclaredField("id"));
        fType = open(postBlock.getDeclaredField("type"));
        fWorld = open(postBlock.getDeclaredField("world"));
        fX = open(postBlock.getDeclaredField("x"));
        fY = open(postBlock.getDeclaredField("y"));
        fZ = open(postBlock.getDeclaredField("z"));
        isMailbox = open(Class.forName(pkg + "PostBlock$Type", true, cl).getDeclaredMethod("isMailbox"));

        Class<?> deliveryCls = Class.forName(pkg + "Delivery", true, cl);
        delivery = open(deliveryCls.getDeclaredConstructor(UUID.class, UUID.class, String.class, UUID.class, UUID.class,
                String.class, String.class, int.class, int.class, int.class, long.class));
        postDelivery = open(post.getClass().getDeclaredMethod("post", deliveryCls));
    }

    private static <T extends java.lang.reflect.AccessibleObject> T open(T member) {
        member.setAccessible(true);
        return member;
    }

    boolean hasMailbox(UUID player) {
        try {
            return !((List<?>) mailboxesOf.invoke(blocks, player)).isEmpty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    ItemStack letter(String from, List<String> pages) {
        try {
            return (ItemStack) sealedLetter.invoke(null, pages, from, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    ItemStack parcel(String from, List<ItemStack> contents) {
        try {
            return (ItemStack) parcel.invoke(null, contents, from);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Puts the mail in the post for this player's first mailbox. False if they have no mailbox. */
    boolean deliver(UUID player, String from, ItemStack mail) {
        try {
            List<?> boxes = (List<?>) mailboxesOf.invoke(blocks, player);
            if (boxes.isEmpty()) return false;
            Object box = boxes.getFirst();
            String world = (String) fWorld.get(box);
            int bx = fX.getInt(box), by = fY.getInt(box), bz = fZ.getInt(box);

            // Start from the nearest drop-off in the same world; else from the mailbox itself.
            Object origin = box;
            long best = Long.MAX_VALUE;
            for (Object b : allBlocks.values()) {
                if ((boolean) isMailbox.invoke(fType.get(b)) || !world.equals(fWorld.get(b))) continue;
                long dx = fX.getInt(b) - bx, dy = fY.getInt(b) - by, dz = fZ.getInt(b) - bz;
                long d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                    origin = b;
                }
            }
            Object d = delivery.newInstance(UUID.randomUUID(), MYSTERY_SENDER, from, player, fId.get(box),
                    encode.invoke(null, mail), world, fX.getInt(origin), fY.getInt(origin), fZ.getInt(origin),
                    System.currentTimeMillis());
            postDelivery.invoke(post, d);
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null (with a log line) if PostOffice is missing or a version this doesn't understand. */
    static PostOfficeBridge connect(MysteryMail plugin) {
        Plugin po = Bukkit.getPluginManager().getPlugin("PostOffice");
        if (po == null || !po.isEnabled()) {
            plugin.getLogger().severe("PostOffice isn't installed or didn't start, so MysteryMail can't send anything.");
            return null;
        }
        try {
            return new PostOfficeBridge(po);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().severe("This PostOffice version (" + po.getPluginMeta().getVersion()
                    + ") isn't supported by MysteryMail: " + e);
            return null;
        }
    }
}
