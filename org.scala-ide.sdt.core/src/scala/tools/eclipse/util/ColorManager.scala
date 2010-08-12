package scala.tools.eclipse.util

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

class ColorManager private() {	
	
	val fColorTable = scala.collection.mutable.Map.empty[RGB,Color];
	
	def getColor(rgb: RGB): Color = {
		var color: Option[Color] = fColorTable.get(rgb);
		if (color == None ) {
			fColorTable(rgb)=new Color(Display.getCurrent(), rgb);
		}
		return fColorTable(rgb);
	}
	
	def dispose() {
		for (c <- fColorTable.values) c.dispose();
	}
}

object ColorManager {
	private val fgColorManager: ColorManager = new ColorManager
	def getDefault(): ColorManager = fgColorManager
}