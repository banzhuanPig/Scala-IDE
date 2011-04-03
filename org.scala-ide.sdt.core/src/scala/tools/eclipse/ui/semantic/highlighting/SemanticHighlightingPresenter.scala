/**
 *
 */
package scala.tools.eclipse
package ui.semantic.highlighting
import org.eclipse.swt.widgets.Display
import scala.tools.eclipse.semantichighlighting._
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport
import org.eclipse.core.runtime.IPath
import ui.ReconciliationParticipant
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.jdt.core.WorkingCopyOwner
import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.jface.preference.{ PreferenceConverter, IPreferenceStore }
import org.eclipse.jface.text.IPainter
import org.eclipse.jface.text.source.{ IAnnotationAccess, AnnotationPainter, IAnnotationModelExtension, Annotation, ISourceViewer }
import org.eclipse.jface.util.{ PropertyChangeEvent, IPropertyChangeListener }
import org.eclipse.swt.SWT
import org.eclipse.ui.{ PlatformUI, IPartListener, IWorkbenchPart }
import org.eclipse.ui.part.FileEditorInput
import scala.collection._
import scala.tools.eclipse.util.Tracer
import scala.tools.eclipse.javaelements.ScalaCompilationUnit
import scala.tools.eclipse.ui.preferences.PropertyChangeListenerProxy
import scala.tools.eclipse.util.{ ColorManager, Annotations, AnnotationsTypes }

/**
 * This class is instantiated by the reconciliationParticipants extension point and
 * simply forwards to the SemanticHighlightingReconciliation  object.
 */
class SemanticHighlightingReconciliationParticipant extends ReconciliationParticipant {

  override def afterReconciliation(scu: ScalaCompilationUnit, monitor: IProgressMonitor, workingCopyOwner: WorkingCopyOwner) {
    SemanticHighlightingReconciliation.afterReconciliation(scu, monitor, workingCopyOwner)
  }

}

/**
 * Manages the SemanticHighlightingPresenter instances for the open editors.
 *
 * Each ScalaCompilationUnit has one associated SemanticHighlightingPresenter,
 * which is created the first time a reconciliation is performed for a
 * compilation unit. When the editor (respectively the IWorkbenchPart) is closed,
 * the SemanticHighlightingPresenter is removed.
 *
 * @author Mirko Stocker
 */
object SemanticHighlightingReconciliation {

  private val participants = new collection.mutable.HashMap[ScalaCompilationUnit, SemanticHighlightingPresenter]
  private val symbolStylers = new collection.mutable.HashMap[ScalaCompilationUnit, SymbolStyler]

  /**
   *  A listener that removes a  SemanticHighlightingPresenter when the part is closed.
   */
  class UnregisteringPartListener(scu: ScalaCompilationUnit) extends IPartListener {

    def partClosed(part: IWorkbenchPart) {
      participants.remove(scu)
    }

    def partActivated(part: IWorkbenchPart) {}
    def partBroughtToTop(part: IWorkbenchPart) {}
    def partDeactivated(part: IWorkbenchPart) {}
    def partOpened(part: IWorkbenchPart) {}
  }

  /**
   * Searches for the Editor that currently displays the compilation unit, then creates
   * an instance of SemanticHighlightingPresenter. A listener is registered at the editor
   * to remove the SemanticHighlightingPresenter when the editor is closed.
   */
  private def createSemanticHighlighterForEditor(scu: ScalaCompilationUnit) = {

    def getPagesWithEditors = {
      PlatformUI.getWorkbench.getWorkbenchWindows flatMap (_.getPages) flatMap { page =>
        page.getEditorReferences.toList map (_.getEditor(false)) collect {
          case editor: ScalaSourceFileEditor => (page, editor)
        }
      }
    }

    getPagesWithEditors flatMap {
      case (page, editor) =>
        Option(editor.getEditorInput) collect {
          case editorInput: FileEditorInput if editorInput.getPath equals scu.getResource.getLocation =>
            page.addPartListener(new UnregisteringPartListener(scu))
            (new SemanticHighlightingPresenter(editorInput, editor.sourceViewer), new SymbolStyler(editor.sourceViewer))
        }
    }
  }

  def afterReconciliation(scu: ScalaCompilationUnit, monitor: IProgressMonitor, workingCopyOwner: WorkingCopyOwner) {

    val firstTimeReconciliation = !participants.contains(scu)

    if (firstTimeReconciliation) {
      createSemanticHighlighterForEditor(scu) foreach {
        case (presenter, styler) =>
          participants(scu) = presenter
          symbolStylers(scu) = styler
      }

    }

    participants(scu).update(scu, monitor, workingCopyOwner)

    scu.doWithSourceFile { (sourceFile, compiler) =>
      val symbolInfos = new SymbolClassifier(sourceFile, compiler).classifySymbols
      symbolStylers(scu).updateSymbolAnnotations(symbolInfos)
    }
  }
}

/**
 * @author Jin Mingjian
 * @author David Bernard
 *
 */
class SemanticHighlightingPresenter(editor: FileEditorInput, sourceViewer: ISourceViewer) {
  import scala.tools.eclipse.ui.preferences.ImplicitsPreferencePage._

  val annotationAccess = new IAnnotationAccess() {
    def getType(annotation: Annotation) = annotation.getType();
    def isMultiLine(annotation: Annotation) = true
    def isTemporary(annotation: Annotation) = true
  }

  private def fFontStyle_BOLD = pluginStore.getBoolean(P_BOLD) match {
    case true => SWT.BOLD
    case _ => SWT.NORMAL
  }

  private def fFontStyle_ITALIC = pluginStore.getBoolean(P_ITALIC) match {
    case true => SWT.ITALIC
    case _ => SWT.NORMAL
  }

  private val P_COLOR = {
    val lookup = new org.eclipse.ui.texteditor.AnnotationPreferenceLookup()
    val pref = lookup.getAnnotationPreference(AnnotationsTypes.Implicits)
    pref.getColorPreferenceKey()
  }

  def fColorValue = ColorManager.getDefault.getColor(PreferenceConverter.getColor(editorsStore, P_COLOR))

  val impTextStyleStrategy = new ImplicitConversionsOrArgsTextStyleStrategy(fFontStyle_BOLD | fFontStyle_ITALIC)

  val painter: AnnotationPainter = {
    val b = new AnnotationPainter(sourceViewer, annotationAccess)
    b.addAnnotationType(AnnotationsTypes.Implicits, AnnotationsTypes.Implicits)
    b.addTextStyleStrategy(AnnotationsTypes.Implicits, impTextStyleStrategy)
    //FIXME settings color of the underline is required to active TextStyle (bug ??, better way ??)
    b.setAnnotationTypeColor(AnnotationsTypes.Implicits, fColorValue)
    sourceViewer.asInstanceOf[org.eclipse.jface.text.TextViewer].addPainter(b)
    sourceViewer.asInstanceOf[org.eclipse.jface.text.TextViewer].addTextPresentationListener(b)
    b
  }

  private val _listener = new IPropertyChangeListener {
    def propertyChange(event: PropertyChangeEvent) {
      val changed = event.getProperty() match {
        case P_BOLD => true
        case P_ITALIC => true
        case P_COLOR => true
        case P_ACTIVE => {
          refresh()
          false
        }
        case _ => false
      }
      if (changed) {
        impTextStyleStrategy.fFontStyle = fFontStyle_BOLD | fFontStyle_ITALIC
        painter.setAnnotationTypeColor(AnnotationsTypes.Implicits, fColorValue)
        painter.paint(IPainter.CONFIGURATION)
      }
    }
  }

  private def refresh() {
    val wb = PlatformUI.getWorkbench()
    for (
      win <- wb.getWorkbenchWindows;
      page <- win.getPages;
      editorRef <- page.getEditorReferences;
      editorIn <- Option(editorRef.getEditorInput)
    ) {
      JavaPlugin.getDefault().getWorkingCopyManager().getWorkingCopy(editorIn) match {
        case scu: ScalaCompilationUnit => update(scu, null, null)
        case _ => //ignore
      }
    }
  }

  protected def pluginStore: IPreferenceStore = ScalaPlugin.plugin.getPreferenceStore
  protected def editorsStore: IPreferenceStore = org.eclipse.ui.editors.text.EditorsUI.getPreferenceStore

  new PropertyChangeListenerProxy(_listener, pluginStore, editorsStore).autoRegister()

  //TODO monitor P_ACTIVATE to register/unregister update
  //TODO monitor P_ACTIVATE to remove existings annotation (true => false) or update openning file (false => true)
  val update = (scu: ScalaCompilationUnit, monitor: IProgressMonitor, workingCopyOwner: WorkingCopyOwner) => {
    //val cu = JavaPlugin.getDefault().getWorkingCopyManager().getWorkingCopy(fEditor.getEditorInput());
    //val scu = cu.asInstanceOf[ScalaCompilationUnit]
    //FIXME : avoid having several SemanticHighlighter notified on single file update (=> move listeners at document level, or move SemanticHighliter at plugin level ?)
    if (scu.getResource.getLocation == editor.getPath.makeAbsolute) {
      scu.doWithSourceFile { (sourceFile, compiler) =>
        val toAdds = new java.util.HashMap[Annotation, org.eclipse.jface.text.Position]

        if (pluginStore.getBoolean(P_ACTIVE)) {
          object viewsCollector extends compiler.Traverser {
            override def traverse(t: compiler.Tree): Unit = t match {
              case v: compiler.ApplyImplicitView =>
                val txt = new String(sourceFile.content, v.pos.startOrPoint, math.max(0, v.pos.endOrPoint - v.pos.startOrPoint)).trim()
                val ia = new ImplicitConversionsOrArgsAnnotation("Implicit conversions found: " + txt + " => " + v.fun.symbol.name + "(" + txt + ")")
                val pos = new org.eclipse.jface.text.Position(v.pos.startOrPoint, txt.length)
                toAdds.put(ia, pos)
                super.traverse(t)
              case v: compiler.ApplyToImplicitArgs =>
                val txt = new String(sourceFile.content, v.pos.startOrPoint, math.max(0, v.pos.endOrPoint - v.pos.startOrPoint)).trim()
                val argsStr = v.args.map(_.symbol.name).mkString("( ", ", ", " )")
                val ia = new ImplicitConversionsOrArgsAnnotation("Implicit arguments found: " + txt + " => " + txt + argsStr)
                val pos = new org.eclipse.jface.text.Position(v.pos.startOrPoint, txt.length)
                toAdds.put(ia, pos)
                super.traverse(t)
              case _ =>
                super.traverse(t)
            }
          }
          viewsCollector.traverse(compiler.body(sourceFile))
        }
        Annotations.update(sourceViewer, AnnotationsTypes.Implicits, toAdds)
      }
    }
  }

}
