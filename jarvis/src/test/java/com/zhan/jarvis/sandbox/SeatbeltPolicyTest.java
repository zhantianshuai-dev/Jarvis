package com.zhan.jarvis.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatbeltPolicyTest {

    @Test
    void workspaceWriteOnlyGrantsWorkspaceAndTempWriteAccess() {
        String policy = SeatbeltPolicy.build("workspace-write", false, true);

        assertTrue(policy.contains("(deny default)"));
        assertTrue(policy.contains("(allow file-read*)"));
        assertTrue(policy.contains("(subpath (param \"WORKSPACE\"))"));
        assertTrue(policy.contains("(subpath (param \"TEMP_DIR\"))"));
        assertTrue(policy.contains("(deny file-write-unlink"));
        assertFalse(policy.contains("(allow network-outbound)\n"));
    }

    @Test
    void readOnlyDoesNotGrantWorkspaceWriteAccess() {
        String policy = SeatbeltPolicy.build("read-only", false, false);

        assertFalse(policy.contains("(subpath (param \"WORKSPACE\"))"));
        assertFalse(policy.contains("(subpath (param \"TEMP_DIR\"))"));
    }

    @Test
    void networkMustBeExplicitlyEnabled() {
        String policy = SeatbeltPolicy.build("workspace-write", true, true);

        assertTrue(policy.contains("(allow network-outbound)\n"));
        assertTrue(policy.contains("(allow network-bind)"));
    }

    @Test
    void commandUsesFixedSeatbeltExecutableAndDefinitionArguments() {
        var command = SeatbeltPolicy.command("policy", Path.of("/workspace"), Path.of("/tmp"), "git status");

        assertEquals(SeatbeltPolicy.SEATBELT_EXECUTABLE, command.getFirst());
        assertTrue(command.contains("-DWORKSPACE=/workspace"));
        assertTrue(command.contains("-DTEMP_DIR=/tmp"));
        assertEquals("git status", command.getLast());
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> SeatbeltPolicy.build("danger-full-access", false, true));
    }
}
