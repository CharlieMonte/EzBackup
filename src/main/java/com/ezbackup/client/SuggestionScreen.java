package com.ezbackup.client;

import com.ezbackup.SuggestionConfig;
import com.ezbackup.SuggestionSender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

import java.util.List;

import static com.ezbackup.client.GuiStyle.COLOR_ERROR;
import static com.ezbackup.client.GuiStyle.COLOR_HIGHLIGHT;
import static com.ezbackup.client.GuiStyle.COLOR_NOTE;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;
import static com.ezbackup.client.GuiStyle.GAP;
import static com.ezbackup.client.GuiStyle.ROW_HEIGHT;

/**
 * The "Send Suggestion" screen, opened by the button in the upper left corner of {@link SettingsScreen}.
 *
 * <pre>
 *        Send me a suggestion, bug report, or a comment =)!
 *        Only this text, the selected type and the EzBackup version / Minecraft version are sent.
 *        +---------------------------------------------+
 *        | multi-line text box (up to 1000 characters) |
 *        +---------------------------------------------+
 *        status (next message countdown / messages left)   123 / 1000
 *                 result of the last send (green / red)
 *                      [ Type: Other v ]      (dropdown: Suggestion, Bug report, Other; default Other)
 *                       [Cancel]  [Send]
 * </pre>
 *
 * The type dropdown sits between the confirmation text and the Cancel / Send buttons. Limits (see
 * {@link SuggestionSender}): one message every 10 minutes, shown as a live countdown, and 12 messages per game
 * session; Send is greyed out while either limit applies.
 *
 * Text only: there is no way to attach a file, screenshot or any other data. Send is greyed out while the box is
 * empty, while a send is in progress, and once Discord has refused the webhook (404 / 401 / 403) this session.
 * The request itself runs on a background thread ({@link SuggestionSender}); the result is handed back to the
 * game thread with {@code Minecraft.execute}, so the screen never freezes. After a successful send the box is
 * emptied; after any failure the text is kept so the player can simply press Send again.
 *
 * Cancel (and Escape) go back to the settings screen at any time, also while a send is running; in that case the
 * result is simply not shown. (Escape first closes the Type list if it is open.)
 *
 * <p>Keyboard focus: the text box starts focused. Tab / Shift+Tab cycle text box, Type, Cancel, Send (buttons that are
 * greyed out are skipped) and back to the text box; Enter or Space presses the focused button. Typing, and Space as a
 * character, only go to the text box while it is focused. Clicking the box (or choosing an entry in the Type list)
 * focuses it and takes the focus away from every button; clicking a button focuses that button instead. The Type
 * list opens with Enter / Space on its button and then takes every key (see {@link DropdownList}).
 */
public class SuggestionScreen extends EzScreen {

    private static final String NOTE = "Only this text, the selected type, the Minecraft version, and EzBackup version are sent to the developer.";
    private static final String SENDING_MESSAGE = "Sending...";

    private static final int BOX_WIDTH = 320;
    private static final int BOX_HEIGHT = 84;
    private static final int MIN_BOX_HEIGHT = 44;
    private static final int BUTTON_WIDTH = 100;
    private static final int TYPE_WIDTH = 140;
    private static final SuggestionSender.Tag[] TAGS = SuggestionSender.Tag.values();
    private static final int MARGIN = 8;
    private static final int FEEDBACK_LINES = 4;


    /** Created once so the typed text survives a window resize (init() runs again then). */
    private final SuggestionTextBox box;
    private Button sendButton;
    private Button typeButton;
    /** Survives a window resize, like the text box. Defaults to Other. */
    private SuggestionSender.Tag selectedTag = SuggestionSender.Tag.OTHER;
    private DropdownList<SuggestionSender.Tag> typeDropdown;
    /**
     * Whether the text box has the keyboard focus. The box is not a widget, so the screen's own focus (which Tab and
     * mouse clicks move between the buttons) knows nothing about it: this flag stands in for it. While it is true the
     * screen's focus is empty, so typing and Space go to the box only, never to a button the player clicked earlier.
     */
    private boolean boxFocused = true;
    /** The button that Tab focused (it is highlighted); null when none. Remembered so a click can un-highlight it. */
    private GuiEventListener tabFocus;
    private List<String> noteLines = List.of();
    private final FeedbackLine feedback = new FeedbackLine();

    /** Width of the text box and the note: BOX_WIDTH, or less on a narrow window. Set in init(). */
    private int boxWidth;
    private int titleY;
    private int noteY;
    private int counterY;
    private int feedbackY;
    private int typeY;

    public SuggestionScreen(Screen parent) {
        super(new TextComponent("Send me a suggestion, bug report, or a comment =)!"), parent);
        this.box = new SuggestionTextBox(Minecraft.getInstance().font, SuggestionConfig.MAX_LENGTH);
    }

    @Override
    protected void init() {
        this.boxFocused = true; // the widgets are rebuilt, so start with the text box focused
        this.tabFocus = null;
        this.setFocused(null);
        this.boxWidth = Math.min(BOX_WIDTH, this.width - 2 * MARGIN);
        int lineHeight = this.font.lineHeight;
        this.noteLines = FeedbackLine.wrap(this.font, NOTE, this.boxWidth);

        // Vertical layout: everything except the text box has a fixed height; the box gets what is left (but
        // never more than BOX_HEIGHT or less than MIN_BOX_HEIGHT), and the whole block is centred.
        int noteHeight = this.noteLines.size() * lineHeight;
        int fixed = lineHeight + 4 + noteHeight + 6 + 3 + lineHeight + 6 + FEEDBACK_LINES * lineHeight + 6 + ROW_HEIGHT + 6 + ROW_HEIGHT;
        int boxHeight = Math.max(MIN_BOX_HEIGHT, Math.min(BOX_HEIGHT, this.height - fixed - 2 * MARGIN));
        int top = Math.max(MARGIN, (this.height - fixed - boxHeight) / 2);

        this.titleY = top;
        this.noteY = this.titleY + lineHeight + 4;
        int boxTop = this.noteY + noteHeight + 6;
        this.box.setBounds((this.width - this.boxWidth) / 2, boxTop, this.boxWidth, boxHeight);
        this.counterY = boxTop + boxHeight + 3;
        this.feedbackY = this.counterY + lineHeight + 6;
        this.typeY = this.feedbackY + FEEDBACK_LINES * lineHeight + 6;
        int buttonY = this.typeY + ROW_HEIGHT + 6;

        // Type dropdown: its button sits above Cancel / Send and below the text box and the confirmation text; the list
        // opens downwards over Cancel / Send, or upwards when there is no room.
        this.typeDropdown = new DropdownList<>(this.font, TYPE_WIDTH, List.of(TAGS),
                SuggestionSender.Tag::label, this::selectTag, () -> this.selectedTag);
        this.typeButton = new Button((this.width - TYPE_WIDTH) / 2, this.typeY, TYPE_WIDTH, ROW_HEIGHT,
                typeLabel(), button -> this.typeDropdown.toggle());
        this.addRenderableWidget(this.typeButton);
        this.typeDropdown.setAnchor(this.typeButton, this.width, this.height);

        int buttonsLeft = (this.width - 2 * BUTTON_WIDTH - GAP) / 2;
        this.addRenderableWidget(new Button(buttonsLeft, buttonY, BUTTON_WIDTH, ROW_HEIGHT,
                new TextComponent("Cancel"), button -> onClose()));
        this.sendButton = new Button(buttonsLeft + BUTTON_WIDTH + GAP, buttonY, BUTTON_WIDTH, ROW_HEIGHT,
                new TextComponent("Send"), button -> send());
        this.addRenderableWidget(this.sendButton);

        if (this.feedback.isEmpty() && !SuggestionSender.isAvailable()) {
            this.feedback.error(SuggestionSender.Outcome.UNAVAILABLE.message());
        }
        updateButtons();
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void send() {
        String text = this.box.getValue();
        String problem = SuggestionSender.textProblem(text);
        if (problem != null) {
            this.feedback.error(problem);
            return;
        }
        // submit() checks the "unavailable" flag first and returns at once; the HTTP request runs in the background.
        String error = SuggestionSender.submit(text, this.selectedTag,
                outcome -> Minecraft.getInstance().execute(() -> onResult(outcome)));
        if (error != null) {
            this.feedback.error(error);
            return;
        }
        this.feedback.info(SENDING_MESSAGE);
        updateButtons();
    }

    /** Runs on the game thread when the background send has finished. */
    private void onResult(SuggestionSender.Outcome outcome) {
        if (outcome == SuggestionSender.Outcome.SENT) {
            this.box.clear();
            this.feedback.success(outcome.message());
        } else {
            this.feedback.error(outcome.message()); // the text stays in the box, so the player can try again (unless unavailable)
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean sending = SuggestionSender.isSending();
        this.box.setEditable(!sending);
        this.typeButton.active = !sending;
        this.sendButton.active = !sending && SuggestionSender.isAvailable() && !this.box.getValue().isBlank()
                && SuggestionSender.limitProblem() == null;
    }

    private TextComponent typeLabel() {
        return new TextComponent("Type: " + this.selectedTag.label());
    }

    private void selectTag(SuggestionSender.Tag tag) {
        this.selectedTag = tag;
        this.typeButton.setMessage(typeLabel());
    }

    /** Text and colour of the left-hand status line under the box: countdown, lockout or messages left. */
    private void renderStatus(PoseStack poseStack, int boxLeft) {
        String text;
        int color;
        if (SuggestionSender.isCapReached()) {
            text = "Limit reached. Restart Minecraft to send more.";
            color = COLOR_ERROR;
        } else {
            int wait = SuggestionSender.cooldownSecondsLeft();
            if (wait > 0) {
                text = "Next message in " + SuggestionSender.formatCountdown(wait)
                        + " (" + SuggestionSender.messagesLeft() + " left)";
                color = COLOR_HIGHLIGHT;
            } else {
                text = "Messages left: " + SuggestionSender.messagesLeft() + " / "
                        + SuggestionConfig.MAX_PER_SESSION;
                color = COLOR_NOTE;
            }
        }
        this.font.draw(poseStack, text, boxLeft, this.counterY, color);
    }

    // ---------------------------------------------------------------------------------------------
    // Screen plumbing
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        this.box.tick();
        updateButtons();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack); // same translucent background as the pause menu

        drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.titleY, COLOR_TEXT);
        for (int i = 0; i < this.noteLines.size(); i++) {
            drawCenteredString(poseStack, this.font, this.noteLines.get(i), this.width / 2,
                    this.noteY + i * this.font.lineHeight, COLOR_NOTE);
        }

        this.box.render(poseStack, this.boxFocused);

        // Character counter, right-aligned under the box.
        int length = this.box.getValue().length();
        String counter = length + " / " + SuggestionConfig.MAX_LENGTH;
        int boxRight = (this.width + this.boxWidth) / 2;
        renderStatus(poseStack, (this.width - this.boxWidth) / 2);
        this.font.draw(poseStack, counter, boxRight - this.font.width(counter), this.counterY,
                length >= SuggestionConfig.MAX_LENGTH ? COLOR_ERROR : COLOR_NOTE);

        // Result of the last action, centred, wrapped to at most four lines.
        this.feedback.render(poseStack, this.font, this.width / 2, this.feedbackY, this.width - 2 * MARGIN,
                FEEDBACK_LINES);

        super.render(poseStack, mouseX, mouseY, partialTick);
        this.typeDropdown.render(poseStack, mouseX, mouseY); // last, so it covers the widgets below it
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard focus: the text box and the buttons share one Tab cycle
    // ---------------------------------------------------------------------------------------------

    /** Gives the text box the focus and takes it from every button (so Space and Enter no longer press one). */
    private void focusBox() {
        clearTabFocus();
        this.setFocused(null);
        this.boxFocused = true;
    }

    /** Un-highlights the button that Tab focused. Its highlight flag is on, so one more changeFocus switches it off. */
    private void clearTabFocus() {
        if (this.tabFocus != null) {
            this.tabFocus.changeFocus(true);
            this.tabFocus = null;
        }
    }

    /**
     * Tab / Shift+Tab: text box, then the buttons in the order they were added (Type, Cancel, Send; disabled ones are
     * skipped), then the text box again.
     */
    private void cycleFocus(boolean forward) {
        if (this.boxFocused) {
            this.boxFocused = false;
            this.setFocused(null);
            if (this.changeFocus(forward)) {
                this.tabFocus = this.getFocused();
            } else {
                this.tabFocus = null;
                this.boxFocused = true; // no button can take the focus
            }
        } else if (this.changeFocus(forward)) {
            this.tabFocus = this.getFocused();
        } else {
            // Left the last (or first) button: changeFocus has already cleared the screen's focus and the button's highlight.
            this.tabFocus = null;
            this.boxFocused = true;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.typeDropdown.isOpen()) {
            return true; // typing while the list is open does nothing
        }
        return this.boxFocused && this.box.charTyped(codePoint) || super.charTyped(codePoint, modifiers);
    }

    /**
     * Keys, in order: an open dropdown takes them all (arrows, Enter / Space, Escape); Tab moves the focus; the text box
     * gets them while it is focused (Escape is not used by the box, so it still goes back); otherwise the focused button
     * handles Enter / Space as usual.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.typeDropdown.keyPressed(keyCode)) {
            return true;
        }
        if (keyCode == GuiKeys.TAB) {
            cycleFocus(!hasShiftDown());
            return true;
        }
        if (this.boxFocused && this.box.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.typeDropdown.mouseClicked(mouseX, mouseY, button)) {
            focusBox(); // the list took the click; afterwards the focus goes back to the text box, not to the Type button
            return true;
        }
        if (button == 0 && this.box.mouseClicked(mouseX, mouseY)) {
            focusBox(); // clicking the box must take the focus away from a button, or Space would press that button
            return true;
        }
        clearTabFocus();
        boolean used = super.mouseClicked(mouseX, mouseY, button); // a clicked button becomes the screen's focus
        this.boxFocused = !used; // a click on empty space gives the focus back to the box
        if (!used) {
            this.setFocused(null);
        }
        return used;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return this.typeDropdown.mouseScrolled(delta) || this.box.mouseScrolled(mouseX, mouseY, delta)
                || super.mouseScrolled(mouseX, mouseY, delta);
    }
}
