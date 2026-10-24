package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Console;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class PasswordResetCommandTest {

    @SuppressWarnings("unchecked")
    private final BiConsumer<String, String> resetPassword = mock(BiConsumer.class);

    @Test
    void resetsSelectedEmailAfterHiddenPasswordConfirmationAndClearsInput() {
        Console console = mock(Console.class);
        char[] password = "new-password".toCharArray();
        char[] confirmation = "new-password".toCharArray();
        when(console.readPassword(anyString(), eq(PasswordChangeService.MINIMUM_PASSWORD_LENGTH)))
                .thenReturn(password);
        when(console.readPassword("Confirm password: ")).thenReturn(confirmation);

        assertThat(PasswordResetCommand.run(new String[] {"member@example.com"}, console, resetPassword)).isZero();

        verify(resetPassword).accept("member@example.com", "new-password");
        assertThat(password).containsOnly('\0');
        assertThat(confirmation).containsOnly('\0');
    }

    @Test
    void rejectsMismatchedOrShortPasswordsWithoutResetting() {
        Console console = mock(Console.class);
        when(console.readPassword(anyString(), eq(PasswordChangeService.MINIMUM_PASSWORD_LENGTH)))
                .thenReturn("new-password".toCharArray(), "short".toCharArray());
        when(console.readPassword("Confirm password: "))
                .thenReturn("other-password".toCharArray(), "short".toCharArray());

        assertThat(PasswordResetCommand.run(new String[] {"member@example.com"}, console, resetPassword)).isEqualTo(2);
        assertThat(PasswordResetCommand.run(new String[] {"member@example.com"}, console, resetPassword)).isEqualTo(2);
        verifyNoInteractions(resetPassword);
    }

    @Test
    void requiresAnEmailAndInteractiveTerminalAndSupportsHelpWithoutDatabaseAccess() {
        assertThat(PasswordResetCommand.run(new String[] {}, null, resetPassword)).isEqualTo(2);
        assertThat(PasswordResetCommand.run(new String[] {"member@example.com", "secret"}, null, resetPassword))
                .isEqualTo(2);
        assertThat(PasswordResetCommand.run(new String[] {"member@example.com"}, null, resetPassword)).isEqualTo(2);
        assertThat(PasswordResetCommand.run(new String[] {"--help"}, null, resetPassword)).isZero();
        verifyNoInteractions(resetPassword);
    }
}
