package click.portl.postoffice;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Writing and reading letters in a letter-style screen (a Minecraft dialog) instead of the book
 * editor: a big text box, a "From" line and buttons. There's no signing; instead the letter goes
 * into an envelope, and an envelope can be opened again (and resealed) any time.
 */
final class LetterDialog {
    private LetterDialog() {}

    /** Right-click on a letter or envelope in the given inventory slot. */
    static void open(PostOfficePlugin plugin, Player p, int slot) {
        ItemStack item = p.getInventory().getItem(slot);
        MailKind kind = MailItems.kind(item);
        if (kind == null) return;
        if (item.getAmount() > 1) {
            // Take one blank letter off the stack to write on.
            ItemStack rest = item.clone();
            rest.setAmount(item.getAmount() - 1);
            item.setAmount(1);
            p.getInventory().setItem(slot, item);
            LetterMenus.giveBack(p, rest);
            item = p.getInventory().getItem(slot);
        }
        if (kind == MailKind.SEALED_LETTER) {
            MailItems.unseal(item);
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        } else if (item.getType() != MailKind.LETTER.material) {
            // Letters from older versions were book & quills: swap for a paper letter.
            ItemStack fresh = MailItems.blankLetter();
            MailItems.writeLetter(fresh, String.join("\n", MailItems.pages(item)), MailItems.sender(item));
            MailItems.setAttachment(fresh, MailItems.attachment(item));
            p.getInventory().setItem(slot, fresh);
            item = fresh;
        }
        String id = MailItems.ensureUnique(item);
        show(plugin, p, id, slot);
    }

    static void show(PostOfficePlugin plugin, Player p, String letterId, int hintSlot) {
        int slot = MailItems.findSlot(p, letterId, hintSlot);
        if (slot < 0) return;
        ItemStack letter = p.getInventory().getItem(slot);
        String text = String.join("\n", MailItems.pages(letter));
        String sender = MailItems.sender(letter);
        ItemStack att = MailItems.attachment(letter);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Msg.get("letter.dialog-hint"), 300));
        if (att != null) {
            body.add(DialogBody.item(att).description(DialogBody.plainMessage(
                    Msg.get("letter.dialog-attached", "item", MailItems.displayName(att)))).build());
        }

        List<DialogInput> inputs = List.of(
                DialogInput.text("text", Msg.get("letter.dialog-text-label"))
                        .initial(text.length() > 1500 ? text.substring(0, 1500) : text)
                        .maxLength(1500)
                        .width(320)
                        .multiline(TextDialogInput.MultilineOptions.create(14, 180))
                        .build(),
                DialogInput.text("sender", Msg.get("letter.dialog-from-label"))
                        .initial(sender == null ? p.getName() : sender)
                        .maxLength(32)
                        .width(200)
                        .build());

        ActionButton envelope = button(plugin, "letter.button-envelope", "letter.button-envelope-tip", (resp, player) -> {
            ItemStack l = save(player, letterId, hintSlot, resp);
            if (l == null) return;
            MailItems.seal(l);
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PUT, 1f, 1.2f);
            Msg.send(player, "letter.sealed");
        });
        ActionButton attach = button(plugin, "letter.button-attach", "letter.button-attach-tip", (resp, player) -> {
            if (save(player, letterId, hintSlot, resp) == null) return;
            int s = MailItems.findSlot(player, letterId, hintSlot);
            if (s >= 0) new LetterMenus.Attach(plugin, player, s).open(player);
        });
        ActionButton keep = button(plugin, "letter.button-save", "letter.button-save-tip", (resp, player) -> {
            if (save(player, letterId, hintSlot, resp) != null) Msg.send(player, "letter.saved");
        });
        ActionButton cancel = ActionButton.builder(Msg.get("letter.button-cancel")).width(100).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Msg.get("letter.dialog-title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(List.of(envelope, attach, keep)).exitAction(cancel).columns(3).build()));
        p.showDialog(dialog);
    }

    private static ActionButton button(PostOfficePlugin plugin, String label, String tip, BiConsumer<DialogResponseView, Player> action) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(30)).build();
        return ActionButton.builder(Msg.get(label))
                .tooltip(Msg.get(tip))
                .width(110)
                .action(DialogAction.customClick((DialogResponseView resp, Audience audience) -> {
                    if (!(audience instanceof Player player)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) action.accept(resp, player);
                    });
                }, once))
                .build();
    }

    /** Writes the text and sender from the screen into the letter. Null if the letter has gone. */
    private static ItemStack save(Player p, String letterId, int hintSlot, DialogResponseView resp) {
        int slot = MailItems.findSlot(p, letterId, hintSlot);
        if (slot < 0) {
            Msg.send(p, "letter.gone");
            return null;
        }
        ItemStack letter = p.getInventory().getItem(slot);
        String text = resp.getText("text");
        String sender = resp.getText("sender");
        sender = sender == null || sender.isBlank() ? p.getName() : PostBlocks.trimName(sender);
        MailItems.writeLetter(letter, text == null ? "" : text, sender);
        return letter;
    }
}
