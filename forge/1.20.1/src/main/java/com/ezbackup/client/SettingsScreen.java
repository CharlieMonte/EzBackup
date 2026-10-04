package com.ezbackup.client;

import com.ezbackup.BackupConfig;
import com.ezbackup.BackupLimits;
import com.ezbackup.BackupManager;
import com.ezbackup.Compression;
import com.ezbackup.EzBackup;
import com.ezbackup.SuggestionSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import static com.ezbackup.client.GuiStyle.COLOR_HINT;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;
import static com.ezbackup.client.GuiStyle.GAP;
import static com.ezbackup.client.GuiStyle.ROW_HEIGHT;

/**
 * The EzBackup settings screen opened by the "B" button on the escape menu.
 *
 * <pre>
 * [Send a Suggestion!] EzBackup                         [Restore] [Backup] [Cancel]   (upper left / upper right corner)
 *          [ Paste File Path Here ] [Save] [Browse]     backup folder (Browse opens the system folder picker)
 *          [ Backup File Name     ] [Save]              backup file name (optional)
 *   Warn on Disconnect: Backup Running   [switch]       warn before leaving a world while a backup runs
 *   Warn on Disconnect: Not Backed Up    [switch]       warn before leaving a world with no backup since it was loaded
 *   Auto Backup When Restoring           [switch]       back up the current world before a restore replaces it
 *   Show Details in /backup list         [switch]       add algorithm, size and date to each /backup list line
 *   Backups Per /backup list Page        [ 10 ]         how many backups one page of /backup list shows
 *                   Default Settings
 *                  [ zstd v ] [ 3 ]                     default algorithm (dropdown) and level (to its right), centred as a pair
 *                       [Done]
 * </pre>
 *
 * Everything except the corner buttons and the five setting rows (labels and controls) is centred on the screen,
 * both sideways and up/down. The two text boxes are centred by themselves; their Save buttons sit beside them. The
 * five setting labels are left-aligned with the left edge of the two text boxes, and their switches / number box line
 * up in a column after the longest label. The default algorithm dropdown and the level box are centred on the screen
 * together as one pair, the level box to the right of the dropdown.
 *
 * Every change is saved immediately through {@link BackupConfig} (config/ezbackup.properties).
 *
 * The three buttons in the upper right corner:
 * <ul>
 *   <li><b>Backup</b> runs {@code /backup create <name> <default algorithm> <default level>}, where the name is the saved
 *       backup file name or, if none is saved, the current date and time. It does nothing
 *       (and is greyed out) while no backup folder has been saved or while a backup is already running.</li>
 *   <li><b>Restore</b> (left of Backup) opens a dropdown of the backups in the backup folder, newest first (it scrolls
 *       when long; "No backups found" when the folder has none). Picking one runs {@code /backup restore <name>} (which
 *       only prints a summary) and then opens the chat with {@code /backup restore <name> confirm} already typed, so the
 *       world is only replaced when the player reads the summary and presses Enter. Greyed out while no backup folder
 *       has been saved or while a backup or a restore is running.</li>
 *   <li><b>Cancel</b> runs {@code /backup cancel}. It is only clickable while a backup is running.</li>
 * </ul>
 * The button in the upper left corner, <b>Send a Suggestion!</b>, opens {@link SuggestionScreen}. It is greyed out when
 * no Discord webhook is configured or once Discord has refused it during this game session.
 *
 * <p>Keyboard: Tab / Shift+Tab move between the widgets, Enter or Space presses the focused button, and Enter in the
 * folder or name box saves it. The two dropdowns (algorithm, restore) open with Enter / Space on their button and then
 * take every key until they close (see {@link DropdownList}). Only one dropdown is open at a time.
 */
public class SettingsScreen extends EzScreen {

    /** Algorithms offered in the dropdown, in display order. Add new algorithms here too. */
    private static final Compression[] CHOICES = {Compression.ZSTD, Compression.ZIP};

    private static final String HINT = "Paste File Path Here";
    private static final String BROWSE_TITLE = "Choose the backup folder";
    private static final String NAME_HINT = "Backup File Name (optional)";
    private static final String SETTINGS_TITLE = "Default Settings";
    private static final String WARN_RUNNING_LABEL = "Warn on Disconnect: Backup Running";
    private static final String WARN_UNSAVED_LABEL = "Warn on Disconnect: Not Backed Up";
    private static final String LIST_DETAILS_LABEL = "Show Details in /backup list";
    private static final String BACKUP_BEFORE_RESTORE_LABEL = "Auto Backup When Restoring";
    private static final String LIST_PAGE_SIZE_LABEL = "Backups Per /backup list Page";
    /** The labels of the rows between the name box and "Default Settings", top to bottom (see addSettingRows). */
    private static final String[] SETTING_LABELS = {
            WARN_RUNNING_LABEL, WARN_UNSAVED_LABEL, BACKUP_BEFORE_RESTORE_LABEL, LIST_DETAILS_LABEL,
            LIST_PAGE_SIZE_LABEL};

    // Vertical layout, in pixels from the top of the path box. blockHeight() adds these up.
    /** Path box to name box. */
    private static final int FIELD_ROW_SPACING = 26;
    /** Distance between the feedback line's top and the box above it. */
    private static final int FEEDBACK_GAP = 8;
    /** Name box to the first setting row; leaves room for the feedback line. */
    private static final int SETTINGS_OFFSET = 44;
    /** Distance between two setting rows. */
    private static final int SETTING_ROW_SPACING = 26;
    /** Extra space between the last setting row and the "Default Settings" title. */
    private static final int TITLE_GAP = 6;
    /** "Default Settings" title to the algorithm / level row. */
    private static final int ALGORITHM_ROW_OFFSET = 14;
    /** Algorithm / level row to the Done button. */
    private static final int DONE_OFFSET = 44;
    /** Height of the font (the 8 in "(ROW_HEIGHT - 8) / 2"): used to centre text in a row. */
    private static final int TEXT_HEIGHT = 8;

    private static final int BOX_WIDTH = 200;
    private static final int SAVE_WIDTH = 50;
    private static final int BROWSE_WIDTH = 56;
    private static final int DONE_WIDTH = 100;
    private static final int ALGORITHM_WIDTH = 70;
    private static final int LEVEL_WIDTH = 40;
    private static final int CORNER_BUTTON_WIDTH = 56;
    private static final int SUGGESTION_BUTTON_WIDTH = 100;
    private static final int CORNER_MARGIN = 6;
    /** Width of the restore dropdown's list, and how many backups it shows before it scrolls. */
    private static final int RESTORE_LIST_WIDTH = 170;
    private static final int RESTORE_LIST_ROWS = 8;
    private static final DateTimeFormatter BACKUP_NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private EditBox pathBox;
    private EditBox nameBox;
    private Button algorithmButton;
    private EditBox levelBox;
    private EditBox pageSizeBox;
    private Button backupButton;
    private Button restoreButton;
    private Button cancelButton;
    private Button suggestionButton;
    private final FeedbackLine feedback = new FeedbackLine();
    private DropdownList<Compression> algorithmDropdown;
    private DropdownList<String> restoreDropdown;

    /** A label drawn left of a setting; {@code y} is the top of the text. */
    private record LabeledRow(String label, int y) {
    }

    private final List<LabeledRow> labels = new ArrayList<>();

    private int titleY;
    private int feedbackY;
    private int settingsTitleY;
    private int labelX;

    public SettingsScreen(Screen parent) {
        super(Component.literal("EzBackup"), parent);
    }

    /** Height of everything from the top of the path box to the bottom of the Done button. */
    private static int blockHeight() {
        return FIELD_ROW_SPACING + SETTINGS_OFFSET + SETTING_LABELS.length * SETTING_ROW_SPACING + TITLE_GAP
                + ALGORITHM_ROW_OFFSET + DONE_OFFSET + ROW_HEIGHT;
    }

    /**
     * Builds the screen top to bottom. The block from the path box down to the Done button is centred on the screen
     * (the corner buttons are kept clear); the title sits halfway between the top of the screen and the path box.
     * Each add...Row method takes the y of its row and returns the y the next part is measured from.
     */
    @Override
    protected void init() {
        this.labels.clear();
        int top = Math.max(CORNER_MARGIN + ROW_HEIGHT + 20, (this.height - blockHeight()) / 2);
        int boxLeft = (this.width - BOX_WIDTH) / 2;
        this.titleY = (top - this.font.lineHeight) / 2;

        addCornerButtons();
        int nameY = addFolderRow(boxLeft, top);
        int settingsY = addNameRow(boxLeft, nameY);
        this.settingsTitleY = addSettingRows(boxLeft, settingsY);
        int algorithmY = addAlgorithmRow(this.settingsTitleY + ALGORITHM_ROW_OFFSET);
        addDoneButton(algorithmY + DONE_OFFSET);
    }

    /**
     * Upper right, left to right: Restore (dropdown), Backup, Cancel. Upper left: Send a Suggestion. Also creates the
     * restore dropdown, which is anchored to the Restore button and fills itself from the backup folder each time it opens.
     */
    private void addCornerButtons() {
        int cancelX = this.width - CORNER_MARGIN - CORNER_BUTTON_WIDTH;
        int backupX = cancelX - GAP / 2 - CORNER_BUTTON_WIDTH;
        // Widgets are added left to right so Tab / arrow-key navigation matches what is on screen: Send a
        // Suggestion, Restore, Backup, Cancel.
        this.suggestionButton = button(CORNER_MARGIN, CORNER_MARGIN, SUGGESTION_BUTTON_WIDTH, Component.literal("Send a Suggestion!"),
                button -> openSuggestionScreen());
        this.addRenderableWidget(this.suggestionButton);

        // Restore sits left of Backup: a dropdown of the backups found in the backup folder.
        int restoreX = backupX - GAP / 2 - CORNER_BUTTON_WIDTH;
        this.restoreDropdown = new DropdownList<>(this.font, RESTORE_LIST_WIDTH, RESTORE_LIST_ROWS,
                this::loadBackupNames, "No backups found", name -> name, this::restoreBackup, () -> null);
        this.restoreButton = button(restoreX, CORNER_MARGIN, CORNER_BUTTON_WIDTH, Component.literal("Restore"),
                button -> toggleRestoreList());
        this.addRenderableWidget(this.restoreButton);
        this.restoreDropdown.setAnchor(this.restoreButton, this.width, this.height);

        this.backupButton = button(backupX, CORNER_MARGIN, CORNER_BUTTON_WIDTH, Component.literal("Backup"),
                button -> startDefaultBackup());
        this.cancelButton = button(cancelX, CORNER_MARGIN, CORNER_BUTTON_WIDTH, Component.literal("Cancel"),
                button -> cancelBackup());
        this.addRenderableWidget(this.backupButton);
        this.addRenderableWidget(this.cancelButton);
        updateCornerButtons();
    }

    /** Backup folder: the box is centred, its Save button sits to the right. Returns the y of the name row. */
    private int addFolderRow(int boxLeft, int y) {
        this.pathBox = new EditBox(this.font, boxLeft, y, BOX_WIDTH, ROW_HEIGHT, Component.literal("Backup path"));
        this.pathBox.setMaxLength(32767); // file paths can be long
        if (BackupConfig.backupDirectory() != null) {
            this.pathBox.setValue(BackupConfig.backupDirectory().toString());
        }
        this.addRenderableWidget(this.pathBox);
        this.setInitialFocus(this.pathBox);

        this.addRenderableWidget(button(saveButtonLeft(boxLeft), y, SAVE_WIDTH, Component.literal("Save"),
                button -> save()));
        // Browse sits right of Save, so the two Save buttons stay in one column.
        this.addRenderableWidget(button(saveButtonLeft(boxLeft) + SAVE_WIDTH + GAP, y, BROWSE_WIDTH, Component.literal("Browse"),
                button -> browse()));
        return y + FIELD_ROW_SPACING;
    }

    /**
     * Backup file name (optional), same width and alignment as the path box. The feedback line sits below it.
     * Returns the y of the first setting row.
     */
    private int addNameRow(int boxLeft, int y) {
        this.nameBox = new EditBox(this.font, boxLeft, y, BOX_WIDTH, ROW_HEIGHT, Component.literal("Backup file name"));
        this.nameBox.setMaxLength(BackupLimits.MAX_NAME_LENGTH);
        this.nameBox.setValue(BackupConfig.backupName());
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(button(saveButtonLeft(boxLeft), y, SAVE_WIDTH, Component.literal("Save"),
                button -> saveName()));

        this.feedbackY = y + ROW_HEIGHT + FEEDBACK_GAP;
        return y + SETTINGS_OFFSET;
    }

    /** True when the mouse is inside the button, also while it is disabled (Button.isMouseOver is false then). */
    private static boolean over(Button button, int mouseX, int mouseY) {
        return mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
    }

    /** A standard button of the usual row height (1.19.3+ builds buttons with Button.builder, not a constructor). */
    private static Button button(int x, int y, int width, Component label, Button.OnPress onPress) {
        return Button.builder(label, onPress).bounds(x, y, width, ROW_HEIGHT).build();
    }

    private static int saveButtonLeft(int boxLeft) {
        return boxLeft + BOX_WIDTH + GAP;
    }

    /**
     * The five labelled rows (see SETTING_LABELS): two warn switches, the auto-backup-when-restoring switch, the list-details
     * switch and the page size box.
     * Labels are left-aligned with the two boxes above; the switches / page size box line up in a column just after
     * the longest label. Returns the y of the "Default Settings" title.
     */
    private int addSettingRows(int boxLeft, int y) {
        int labelWidth = 0;
        for (String label : SETTING_LABELS) {
            labelWidth = Math.max(labelWidth, this.font.width(label));
        }
        int controlLeft = boxLeft + labelWidth + GAP;
        this.labelX = boxLeft;

        addSwitchRow(WARN_RUNNING_LABEL, controlLeft, y, BackupConfig::warnMidBackup, this::toggleWarnRunning);
        y += SETTING_ROW_SPACING;
        addSwitchRow(WARN_UNSAVED_LABEL, controlLeft, y, BackupConfig::warnNoBackup, this::toggleWarnUnsaved);
        y += SETTING_ROW_SPACING;
        addSwitchRow(BACKUP_BEFORE_RESTORE_LABEL, controlLeft, y, BackupConfig::backupBeforeRestore,
                this::toggleBackupBeforeRestore);
        y += SETTING_ROW_SPACING;
        addSwitchRow(LIST_DETAILS_LABEL, controlLeft, y, BackupConfig::listDetails, this::toggleListDetails);
        y += SETTING_ROW_SPACING;

        addLabel(LIST_PAGE_SIZE_LABEL, y);
        this.pageSizeBox = numberBox(controlLeft, y, SwitchButton.WIDTH, "Backups per list page",
                BackupConfig.listPageSize(), this::onPageSizeEdited);
        this.addRenderableWidget(this.pageSizeBox);
        y += SETTING_ROW_SPACING;

        return y + TITLE_GAP;
    }

    private void addSwitchRow(String label, int x, int y, BooleanSupplier state, Runnable onToggle) {
        addLabel(label, y);
        this.addRenderableWidget(new SwitchButton(x, y, state, button -> onToggle.run()));
    }

    /** Remembers a label to draw left of the row whose top is {@code rowY}, vertically centred on it. */
    private void addLabel(String label, int rowY) {
        this.labels.add(new LabeledRow(label, rowY + (ROW_HEIGHT - TEXT_HEIGHT) / 2));
    }

    /** A box for a 1-2 digit number. The responder is set after setValue so it does not fire during init. */
    private EditBox numberBox(int x, int y, int width, String name, int initial, Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, width, ROW_HEIGHT, Component.literal(name));
        box.setMaxLength(2);
        box.setFilter(text -> text.length() <= 2 && text.chars().allMatch(Character::isDigit));
        box.setValue(Integer.toString(initial));
        box.setResponder(responder);
        return box;
    }

    /** Default algorithm (dropdown) and level box, centred on the screen together as one pair. Returns the row's y. */
    private int addAlgorithmRow(int y) {
        int algorithmLeft = (this.width - ALGORITHM_WIDTH - GAP - LEVEL_WIDTH) / 2;

        this.algorithmDropdown = new DropdownList<>(this.font, ALGORITHM_WIDTH, List.of(CHOICES),
                Compression::commandName, this::selectAlgorithm, BackupConfig::defaultAlgorithm);
        this.algorithmButton = button(algorithmLeft, y, ALGORITHM_WIDTH, algorithmLabel(BackupConfig.defaultAlgorithm()),
                button -> toggleAlgorithmList());
        this.addRenderableWidget(this.algorithmButton);
        this.algorithmDropdown.setAnchor(this.algorithmButton, this.width, this.height);

        this.levelBox = numberBox(algorithmLeft + ALGORITHM_WIDTH + GAP, y, LEVEL_WIDTH, "Compression level",
                BackupConfig.defaultLevel(), this::onLevelEdited);
        this.addRenderableWidget(this.levelBox);
        return y;
    }

    /** Done button near the bottom. */
    private void addDoneButton(int y) {
        this.addRenderableWidget(button((this.width - DONE_WIDTH) / 2, Math.min(this.height - 28, y),
                DONE_WIDTH, Component.literal("Done"), button -> onClose()));
    }

    private static Component algorithmLabel(Compression algorithm) {
        return Component.literal(algorithm.commandName());
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void save() {
        String error = BackupConfig.setBackupDirectory(this.pathBox.getValue());
        if (error == null) {
            this.feedback.success("Saved! Backups will be stored in: " + BackupConfig.backupDirectory());
        } else {
            this.feedback.error(error);
        }
    }

    /**
     * Browse pressed: opens the operating system's folder picker (LWJGL's tinyfd, which Minecraft already ships), starting
     * in the saved folder if there is one. A chosen folder is put in the path box and saved at once, exactly as if it had
     * been pasted and Save pressed. Cancelling the picker changes nothing. The call blocks until the picker closes.
     */
    private void browse() {
        String start = null;
        Path saved = BackupConfig.backupDirectory();
        if (saved != null && Files.isDirectory(saved)) {
            start = saved.toAbsolutePath() + File.separator;
        }
        String chosen;
        try {
            chosen = TinyFileDialogs.tinyfd_selectFolderDialog(BROWSE_TITLE, start);
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Could not open the folder picker", t);
            this.feedback.error("Could not open the folder picker. Paste the path into the box instead.");
            return;
        }
        if (chosen == null || chosen.isBlank()) {
            return; // cancelled (or the system has no picker, e.g. Linux without zenity / kdialog)
        }
        this.pathBox.setValue(chosen);
        save();
    }

    /** True when the "Backup" button may do something: a folder is saved and no backup is running. */
    private static boolean canStartBackup() {
        return BackupConfig.backupDirectory() != null && !BackupManager.isRunning();
    }

    /** True when the "Restore" dropdown may be used: a folder is saved and no backup or restore is running. */
    private static boolean canRestore() {
        return BackupConfig.backupDirectory() != null && !BackupManager.isRunning() && !BackupManager.isRestoring();
    }

    /** Restore button pressed (mouse, or Enter / Space while focused): opens or closes the restore list, closing the other one. */
    private void toggleRestoreList() {
        this.algorithmDropdown.close();
        this.restoreDropdown.toggle();
    }

    /** Algorithm button pressed (mouse, or Enter / Space while focused): opens or closes the algorithm list, closing the other one. */
    private void toggleAlgorithmList() {
        this.restoreDropdown.close();
        this.algorithmDropdown.toggle();
    }

    /**
     * The backup names for the restore dropdown, newest first, read from the backup folder each time the list opens.
     * A folder that cannot be read gives an empty list and an error in the feedback line.
     */
    private List<String> loadBackupNames() {
        try {
            return BackupManager.backupNames();
        } catch (IOException e) {
            this.feedback.error("Could not read the backup folder: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Picked in the restore dropdown: runs "/backup restore <name>" (which only prints a summary and changes nothing),
     * then opens the chat with "/backup restore <name> confirm" already typed, so the restore only happens when the
     * player reads the summary and presses Enter. Does nothing if a restore is not possible right now.
     */
    private void restoreBackup(String name) {
        if (!canRestore()) {
            return;
        }
        if (runCommand("/backup restore " + name)) {
            Minecraft.getInstance().setScreen(new ChatScreen("/backup restore " + name + " confirm"));
        }
    }

    /** Greys the corner buttons in or out; closes the restore list if restoring is no longer possible. Runs every tick and frame. */
    private void updateCornerButtons() {
        this.backupButton.active = canStartBackup();
        this.restoreButton.active = canRestore();
        if (!this.restoreButton.active) {
            this.restoreDropdown.close();
        }
        this.cancelButton.active = BackupManager.isRunning();
        this.suggestionButton.active = SuggestionSender.isAvailable();
    }

    private void openSuggestionScreen() {
        if (SuggestionSender.isAvailable()) {
            Minecraft.getInstance().setScreen(new SuggestionScreen(this));
        }
    }

    /** "Zstd 3": the saved default algorithm and level, read as one snapshot. */
    private static String describeDefaults() {
        BackupConfig.Settings defaults = BackupConfig.settings();
        return defaults.algorithm().describe(defaults.level());
    }

    /** Runs a chat command as the player. Returns false (and does nothing) when there is no player. */
    private static boolean runCommand(String command) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        // 1.19.3+: commands are sent without the leading slash through the connection (LocalPlayer.chat was removed)
        player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
        return true;
    }

    /** Runs "/backup create <name> <default algorithm> <default level>" as the player. Does nothing if not possible. */
    private void startDefaultBackup() {
        if (!canStartBackup()) {
            return;
        }
        String name = BackupConfig.backupName();
        if (name.isEmpty()) {
            name = "backup-" + LocalDateTime.now().format(BACKUP_NAME_TIME);
        }
        String problem = BackupManager.nameProblem(name);
        if (problem != null) {
            this.feedback.error(problem);
            return;
        }
        BackupConfig.Settings defaults = BackupConfig.settings();
        Compression algorithm = defaults.algorithm();
        int level = defaults.level();
        if (runCommand("/backup create " + name + " " + algorithm.commandName() + " " + level)) {
            this.feedback.success("Backup started: " + name + " (" + algorithm.describe(level) + "). Progress is shown in chat.");
        }
    }

    /** Runs "/backup cancel" as the player. Does nothing if no backup is running. */
    private void cancelBackup() {
        if (BackupManager.isRunning() && runCommand("/backup cancel")) {
            this.feedback.success("Cancelling the running backup...");
        }
    }

    private void saveName() {
        String error = BackupConfig.setBackupName(this.nameBox.getValue());
        if (error != null) {
            this.feedback.error(error);
        } else if (BackupConfig.backupName().isEmpty()) {
            this.feedback.success("No file name set. Backups will be named after the date and time.");
        } else {
            this.feedback.success("Saved! The Backup button will name backups: " + BackupConfig.backupName());
        }
    }

    private void selectAlgorithm(Compression algorithm) {
        String error = BackupConfig.setDefaultAlgorithm(algorithm);
        if (error != null) {
            this.feedback.error(error);
            return;
        }
        this.algorithmButton.setMessage(algorithmLabel(algorithm));
        // The level may have been moved into the new algorithm's range.
        this.levelBox.setValue(Integer.toString(BackupConfig.defaultLevel()));
        this.feedback.success("Default is now " + algorithm.describe(BackupConfig.defaultLevel()) + ".");
    }

    private void onLevelEdited(String text) {
        onNumberEdited(text, BackupConfig::defaultLevel, BackupConfig::setDefaultLevel,
                level -> "Default is now " + BackupConfig.defaultAlgorithm().describe(level) + ".");
    }

    private void toggleListDetails() {
        String error = BackupConfig.setListDetails(!BackupConfig.listDetails());
        if (error != null) {
            this.feedback.error(error);
        }
    }

    private void onPageSizeEdited(String text) {
        onNumberEdited(text, BackupConfig::listPageSize, BackupConfig::setListPageSize,
                size -> "/backup list now shows " + size + " backup" + (size == 1 ? "" : "s") + " per page.");
    }

    /**
     * A number box was edited. An empty box is ignored (the player is retyping; the saved value stays until a number
     * is entered), as is the value that is already saved. Otherwise the number is saved through {@code setter}, which
     * returns an error sentence or null, and the result is shown in the feedback line.
     */
    private void onNumberEdited(String text, IntSupplier saved, IntFunction<String> setter,
                                IntFunction<String> successMessage) {
        if (text.isEmpty()) {
            return;
        }
        int value = Integer.parseInt(text); // the box filter guarantees 1-2 digits
        if (value == saved.getAsInt()) {
            return;
        }
        String error = setter.apply(value);
        if (error == null) {
            this.feedback.success(successMessage.apply(value));
        } else {
            this.feedback.error(error);
        }
    }

    private void toggleBackupBeforeRestore() {
        String error = BackupConfig.setBackupBeforeRestore(!BackupConfig.backupBeforeRestore());
        if (error != null) {
            this.feedback.error(error);
        }
    }

    private void toggleWarnRunning() {
        String error = BackupConfig.setWarnMidBackup(!BackupConfig.warnMidBackup());
        if (error != null) {
            this.feedback.error(error);
        }
    }

    private void toggleWarnUnsaved() {
        String error = BackupConfig.setWarnNoBackup(!BackupConfig.warnNoBackup());
        if (error != null) {
            this.feedback.error(error);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.algorithmDropdown.mouseClicked(mouseX, mouseY, button)
                || this.restoreDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true; // an open list takes the click (selects an entry and/or closes)
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return this.algorithmDropdown.mouseScrolled(delta) || this.restoreDropdown.mouseScrolled(delta)
                || super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** While a dropdown is open, typed characters must not reach the text boxes behind it. */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.algorithmDropdown.isOpen() || this.restoreDropdown.isOpen()) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ---------------------------------------------------------------------------------------------
    // Screen plumbing
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        updateCornerButtons();
        this.pathBox.tick();
        this.nameBox.tick();
        this.levelBox.tick();
        this.pageSizeBox.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics); // same translucent background as the pause menu

        // Text that sits behind the widgets.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.titleY, COLOR_TEXT);
        graphics.drawCenteredString(this.font, SETTINGS_TITLE, this.width / 2, this.settingsTitleY, COLOR_TEXT);
        for (LabeledRow row : this.labels) {
            graphics.drawString(this.font, row.label(), this.labelX, row.y(), COLOR_TEXT, false);
        }

        updateCornerButtons();
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltips for the corner buttons (not while a dropdown list is open and covering things).
        if (!this.algorithmDropdown.isOpen() && !this.restoreDropdown.isOpen()) {
            if (over(this.restoreButton, mouseX, mouseY)) {
                String tip = BackupConfig.backupDirectory() == null
                        ? "Save a backup folder first"
                        : BackupManager.isRunning() ? "A backup is running"
                        : BackupManager.isRestoring() ? "A restore is running"
                        : "Pick a backup to restore";
                graphics.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
            } else if (over(this.backupButton, mouseX, mouseY)) {
                String tip = BackupConfig.backupDirectory() == null
                        ? "Save a backup folder first"
                        : BackupManager.isRunning() ? "A backup is already running"
                        : "Back up now with " + describeDefaults();
                graphics.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
            } else if (over(this.cancelButton, mouseX, mouseY)) {
                graphics.renderTooltip(this.font, Component.literal(BackupManager.isRunning()
                        ? "Cancel the running backup" : "No backup is running"), mouseX, mouseY);
            } else if (over(this.suggestionButton, mouseX, mouseY)) {
                graphics.renderTooltip(this.font, Component.literal(SuggestionSender.isAvailable()
                        ? "Send a text-only suggestion or bug report to the developer"
                        : "The webhook may be inactive. Check for a newer EzBackup version"), mouseX, mouseY);
            }
        }

        // Placeholder text while a box is empty and not being edited.
        if (this.pathBox.getValue().isEmpty() && !this.pathBox.isFocused()) {
            graphics.drawString(this.font, HINT, this.pathBox.getX() + 4,
                    this.pathBox.getY() + (ROW_HEIGHT - TEXT_HEIGHT) / 2, COLOR_HINT, false);
        }
        if (this.nameBox.getValue().isEmpty() && !this.nameBox.isFocused()) {
            graphics.drawString(this.font, NAME_HINT, this.nameBox.getX() + 4,
                    this.nameBox.getY() + (ROW_HEIGHT - TEXT_HEIGHT) / 2, COLOR_HINT, false);
        }

        // Result of the last change, centered under the two boxes.
        // One unwrapped line (huge width, 1 line).
        this.feedback.render(graphics, this.font, this.width / 2, this.feedbackY, Integer.MAX_VALUE, 1);

        this.algorithmDropdown.render(graphics, mouseX, mouseY);
        this.restoreDropdown.render(graphics, mouseX, mouseY);
    }

    /**
     * An open dropdown gets every key first (arrows, Enter / Space, Escape ...). Otherwise Enter in the path or name box
     * behaves like clicking its Save button; everything else (Tab, Escape, Enter / Space on a focused button) is the
     * normal screen handling.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // An open dropdown gets every key first (arrows, Enter / Space, Escape, ...).
        if (this.algorithmDropdown.keyPressed(keyCode) || this.restoreDropdown.keyPressed(keyCode)) {
            return true;
        }
        if (GuiKeys.isEnter(keyCode) && this.pathBox.isFocused()) {
            save();
            return true;
        }
        if (GuiKeys.isEnter(keyCode) && this.nameBox.isFocused()) {
            saveName();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
