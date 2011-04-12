/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$
package scala.tools.eclipse

import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.Path
import org.eclipse.jdt.core.{ ICodeAssist, IJavaElement }
import org.eclipse.jface.text.{ IRegion, ITextViewer }
import org.eclipse.jface.text.hyperlink.{ AbstractHyperlinkDetector, IHyperlink }
import org.eclipse.jdt.internal.core.{ ClassFile, Openable }
import org.eclipse.ui.texteditor.ITextEditor
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit
import org.eclipse.jdt.ui.actions.SelectionDispatchAction
import org.eclipse.jdt.internal.ui.javaeditor.{ EditorUtility, JavaElementHyperlink }
import org.eclipse.jdt.ui.actions.OpenAction
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor

import scala.reflect.generic.Flags._
import scala.tools.nsc.io.AbstractFile

import javaelements.{ ScalaSourceFile, ScalaClassFile, ScalaCompilationUnit, ScalaSelectionEngine, ScalaSelectionRequestor }
import util.Logger

class ScalaHyperlinkDetector extends AbstractHyperlinkDetector with Logger {
  def detectHyperlinks(viewer: ITextViewer, region: IRegion, canShowMultipleHyperlinks: Boolean): Array[IHyperlink] = {
    val textEditor = getAdapter(classOf[ITextEditor]).asInstanceOf[ITextEditor]
    detectHyperlinks(textEditor, region, canShowMultipleHyperlinks)
  }

  case class Hyperlink(file: Openable, pos: Int)(wordRegion: IRegion) extends IHyperlink {
    def getHyperlinkRegion = wordRegion
    def getTypeLabel = null
    def getHyperlinkText = "Open Declaration"
    def open = {
      EditorUtility.openInEditor(file, true) match {
        case editor: ITextEditor => editor.selectAndReveal(pos, 0)
        case _                   =>
      }
    }
  }

  def detectHyperlinks(textEditor: ITextEditor, region: IRegion, canShowMultipleHyperlinks: Boolean): Array[IHyperlink] = {
    if (textEditor == null) // can be null if generated through ScalaPreviewerFactory
      null
    else
      EditorUtility.getEditorInputJavaElement(textEditor, false) match {
        case scu: ScalaCompilationUnit =>
          scu.withSourceFile({ (sourceFile, compiler) =>
            val wordRegion = ScalaWordFinder.findWord(scu.getContents, region.getOffset)
            if (wordRegion == null || wordRegion.getLength == 0)
              null
            else {
              val pos = compiler.rangePos(sourceFile, wordRegion.getOffset, wordRegion.getOffset, wordRegion.getOffset + wordRegion.getLength)

              val response = new compiler.Response[compiler.Tree]
              compiler.askTypeAt(pos, response)
              val typed = response.get

              log("detectHyperlinks: wordRegion = " + wordRegion)
              val hyperlinks: Option[Hyperlink] = compiler.ask { () =>
                import compiler.{ log => _, _ }

                typed.left.toOption map {
                  case Import(expr, sels) => sels find (_.namePos >= pos.start) map (sel => expr.tpe.member(sel.name)) getOrElse NoSymbol
                  case Annotated(atp, _)  => atp.symbol
                  case st: SymTree        => st.symbol
                  case t                  => log("unhandled tree " + t); NoSymbol
                } flatMap { sym =>
                  if (sym.isPackage || sym == NoSymbol || sym.isJavaDefined)
                    None
                  else
                    compiler.locate(sym, scu) map { case (f, pos) => Hyperlink(f, pos)(wordRegion) }
                }
              }
              if (!hyperlinks.isDefined) {
                log("!!! Falling back to selection engine for %s!".format(typed.left), Category.ERROR)
                codeSelect(textEditor, wordRegion, scu)
              } else
                Array(hyperlinks.get: IHyperlink)
            }
          })(null)

        case _ => null
      }
  }

  //Default path used for selecting.
  def codeSelect(textEditor: ITextEditor, wordRegion: IRegion, scu: ScalaCompilationUnit): Array[IHyperlink] = {
    try {
      val environment = scu.newSearchableEnvironment()
      val requestor = new ScalaSelectionRequestor(environment.nameLookup, scu)
      val engine = new ScalaSelectionEngine(environment, requestor, scu.getJavaProject.getOptions(true))
      val offset = wordRegion.getOffset
      engine.select(scu, offset, offset + wordRegion.getLength - 1)
      val elements = requestor.getElements

      if (elements.length == 0)
        null
      else {
        val qualify = elements.length > 1
        val openAction = new OpenAction(textEditor.asInstanceOf[JavaEditor])
        elements.map(new JavaElementHyperlink(wordRegion, openAction, _, qualify))
      }
    } catch {
      case _ => null
    }
  }
}
