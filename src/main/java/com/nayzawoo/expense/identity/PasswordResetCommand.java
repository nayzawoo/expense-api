package com.nayzawoo.expense.identity;

import com.nayzawoo.expense.ExpenseApiApplication;
import java.io.Console;
import java.util.Arrays;
import java.util.function.BiConsumer;
import org.springframework.boot.SpringApplication;

/** Local operator command; passwords are read only from the terminal. */
public final class PasswordResetCommand {

    private PasswordResetCommand() {}

    public static void main(String[] args) {
        System.exit(run(args, System.console(), PasswordResetCommand::resetPassword));
    }

    static int run(String[] args, Console console, BiConsumer<String, String> resetPassword) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: sh scripts/reset-password.sh EMAIL");
            System.out.println("Prompts for a temporary password, revokes sessions, and requires a password change at login.");
            return 0;
        }
        if (args.length != 1 || args[0].isBlank() || args[0].startsWith("-")) {
            System.err.println("Usage: sh scripts/reset-password.sh EMAIL");
            return 2;
        }
        if (console == null) {
            System.err.println("Run this command in an interactive terminal to enter the password securely.");
            return 2;
        }
        char[] password = null;
        char[] confirmation = null;
        try {
            password = console.readPassword("New temporary password (%d+ characters): ",
                    PasswordChangeService.MINIMUM_PASSWORD_LENGTH);
            confirmation = console.readPassword("Confirm password: ");
            if (password == null || confirmation == null) {
                System.err.println("Password reset cancelled.");
                return 2;
            }
            if (password.length < PasswordChangeService.MINIMUM_PASSWORD_LENGTH
                    || new String(password).isBlank()) {
                System.err.println("Password must contain at least "
                        + PasswordChangeService.MINIMUM_PASSWORD_LENGTH + " characters and cannot be blank.");
                return 2;
            }
            if (!Arrays.equals(password, confirmation)) {
                System.err.println("Passwords do not match.");
                return 2;
            }
            resetPassword.accept(args[0], new String(password));
            System.out.println("Password reset. Log in with the temporary password, then choose a new password.");
            return 0;
        } catch (RuntimeException exception) {
            // Startup errors can contain configuration details; do not print them here.
            System.err.println("Password reset failed. Check that the user is active and the database is available.");
            return 1;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }

    private static void resetPassword(String email, String password) {
        try (var context = SpringApplication.run(ExpenseApiApplication.class,
                "--server.port=0", "--server.address=127.0.0.1",
                "--expense.bootstrap.enabled=false", "--expense.recovery.enabled=false")) {
            context.getBean(RecoveryService.class).resetPassword(email, password);
        }
    }
}
