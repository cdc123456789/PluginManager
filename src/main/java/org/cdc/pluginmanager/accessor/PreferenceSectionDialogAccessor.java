package org.cdc.pluginmanager.accessor;

import net.mcreator.ui.dialogs.preferences.PreferencesDialog;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * &#064;Developer  user
 * &#064;CreatedIn  2026/11/12
 */
public class PreferenceSectionDialogAccessor {
    private final PreferencesDialog preferencesDialog;
    private final Method addSection;
    private final Field preferences;

    public PreferenceSectionDialogAccessor(PreferencesDialog preferencesDialog) {
        this.preferencesDialog = preferencesDialog;
        try {
            this.addSection = PreferencesDialog.class.getDeclaredMethod("addSection", String.class);
            this.preferences = PreferencesDialog.class.getDeclaredField("preferences");
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        addSection.setAccessible(true);
        preferences.setAccessible(true);
    }

    public void addSection(String name) {
        try {
            addSection.invoke(preferencesDialog, name);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public JPanel getPreferences() {
        try {
            return (JPanel) preferences.get(preferencesDialog);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void addSectionPanel(String name, JPanel jPanel) {
        addSection(name);
        getPreferences().add(jPanel, name);
    }
}
