package org.example;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.callbacks.AcceptedCallbackHandler;
import org.example.callbacks.StartCallbackHandler;
import org.example.callbacks.ToggleVisibilityCallbackHandler;
import org.example.commands.*;
import org.example.dialogs.ProfileDialogHandler;
import org.example.dialogs.PromoteUserDialogHandler;
import org.example.dialogs.SupportDialogHandler;
import org.example.interfaces.BotCommandHandler;
import org.example.enums.DialogMode;
import org.example.interfaces.CallbackQueryHandler;
import org.example.interfaces.DialogHandler;
import org.example.models.UserInfo;
import org.example.services.SupportRequestService;
import org.example.services.UserInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

@Component
@NoArgsConstructor
public class AuroraBot extends MultiSessionTelegramBot implements CommandLineRunner {
    private final Map<String, BotCommandHandler> commandHandlers = new HashMap<>();
    private final Map<String, CallbackQueryHandler> callbackHandlers = new HashMap<>();

    @Getter
    private final ConcurrentHashMap<Long, DialogMode> userModes = new ConcurrentHashMap<>();

    @Getter
    private final ConcurrentHashMap<Long, UserInfo> userInfos = new ConcurrentHashMap<>();

    @Getter
    private final ConcurrentHashMap<Long, Integer> userQuestionCounts = new ConcurrentHashMap<>();

    private UserInfoService userInfoService;
    private SupportRequestService supportRequestService;

    @Autowired
    public AuroraBot(UserInfoService userInfoService, SupportRequestService supportRequestService) {
        this.userInfoService = userInfoService;
        this.supportRequestService = supportRequestService;
    }

    @Value("${telegram.bot.name}")
    private String botName;

    @Value("${telegram.bot.token}")
    private String botToken;

    @PostConstruct
    private void initializeBot() {
        initialize(botName, botToken);
    }

    @Override
    public void run(String... args) throws Exception {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(this);
        registerCommands();
        registerCallbackHandlers();
        setMyCommands();
    }

    private void registerCommands() {
        commandHandlers.put("/start", new StartCommand(this));
        commandHandlers.put("/profile", new ProfileCommand(this, userInfoService));
        commandHandlers.put("/help", new HelpCommand(this));
        commandHandlers.put("/support", new SupportCommand(this, supportRequestService));
        commandHandlers.put("/admin", new AdminCommand(this, userInfoService));
        commandHandlers.put("/list_admins", new AdminsListCommand(this, userInfoService));
        commandHandlers.put("/promote", new PromoteCommand(this, userInfoService));
    }

    private void registerCallbackHandlers() {
        callbackHandlers.put("start", new StartCallbackHandler(this));
        callbackHandlers.put("accepted", new AcceptedCallbackHandler(this, userInfoService));
        callbackHandlers.put("toggle_visibility", new ToggleVisibilityCallbackHandler(this, userInfoService));
    }

    private void setMyCommands() {
        List<BotCommand> commands = List.of(
                new BotCommand("/start", "Заполнить анкету заново"),
                new BotCommand("/profile", "Моя анкета"),
                new BotCommand("/help", "Помощь")
        );

        try {
            execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUpdateEventReceived(Update update) {
        Long userId = getUserId(update);
        String message = getMessageText(userId);
        String callbackData = getCallbackQueryButtonKey(userId);

        if (message != null && message.startsWith("/")) {
            handleCommand(userId, message);
        } else if (callbackData != null && !callbackData.isEmpty()) {
            handleCallbackQuery(userId, callbackData, update);
        } else if (message != null && !message.isEmpty()) {
            handleDialogMode(userId, message);
        }
    }

    private void handleCommand(Long userId, String command) {
        BotCommandHandler handler = commandHandlers.get(command);
        if (handler != null) {
            handler.handle(userId);
        } else {
            sendTextMessage(userId, "Неизвестная команда. Попробуйте /start.");
        }
    }

    private void handleCallbackQuery(Long userId, String callbackData, Update update) {
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        CallbackQueryHandler handler = callbackHandlers.get(callbackData);
        if (handler != null) {
            handler.handle(userId, messageId);
        } else {
            sendTextMessage(userId, "Неизвестная команда. Попробуйте /start.");
        }
    }

    private void handleDialogMode(Long userId, String message) {
        DialogMode currentMode = userModes.getOrDefault(userId, null);
        if (currentMode == null) {
            sendTextMessage(userId, "Пожалуйста, начните с команды /start.");
            return;
        }

        DialogHandler handler = getDialogHandler(currentMode);
        if (handler != null) {
            handler.handle(userId, message);
        } else {
            sendTextMessage(userId, "Неизвестный режим диалога.");
        }
    }

    private DialogHandler getDialogHandler(DialogMode mode) {
        return switch (mode) {
            case PROFILE -> new ProfileDialogHandler(this, userInfoService);
            case SUPPORT -> new SupportDialogHandler(this, supportRequestService);
            case PROMOTE -> new PromoteUserDialogHandler(this, userInfoService);
        };
    }
}
