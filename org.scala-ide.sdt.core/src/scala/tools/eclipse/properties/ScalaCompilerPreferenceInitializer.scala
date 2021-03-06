/*
 * Copyright 2005-2010 LAMP/EPFL
 * @author Josh Suereth
 */
// $Id$

package scala.tools.eclipse.properties

import org.eclipse.core.runtime.preferences.{ AbstractPreferenceInitializer, DefaultScope }

import scala.tools.nsc.Settings

import scala.tools.eclipse.ScalaPlugin
import scala.tools.eclipse.SettingConverterUtil._
import scala.tools.eclipse.util.IDESettings

/**
 * This is responsible for initializing Scala Compiler
 * Preferences to their default values.
 */
class ScalaCompilerPreferenceInitializer extends AbstractPreferenceInitializer {
  
  /** Actually initializes preferences */
  def initializeDefaultPreferences() : Unit = {
	  
    ScalaPlugin.plugin.check {
      val settings = new Settings
      val node = new DefaultScope().getNode(ScalaPlugin.plugin.pluginId)
      val store = ScalaPlugin.plugin.getPluginPreferences

      IDESettings.shownSettings(settings).foreach {
        setting =>
          val preferenceName = convertNameToProperty(setting.name)
          setting match {
            case bs : Settings#BooleanSetting => node.put(preferenceName, "false")
            case is : Settings#IntSetting => node.put(preferenceName, is.default.toString)
            case ss : Settings#StringSetting => node.put(preferenceName, ss.default)
            case ms : Settings#MultiStringSetting => node.put(preferenceName, "")
            case cs : Settings#ChoiceSetting => node.put(preferenceName, cs.default)
          }
      }
    }
  }
}
