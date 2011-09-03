
/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import org.eclipse.jface.util.PropertyChangeEvent
import org.eclipse.jface.util.IPropertyChangeListener
import java.util.ResourceBundle
import org.eclipse.core.runtime.{ IAdaptable, IProgressMonitor }
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor
import org.eclipse.jdt.internal.ui.javaeditor.selectionactions._
import org.eclipse.jdt.internal.ui.search.IOccurrencesFinder._
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.text._
import org.eclipse.jface.text.source._
import org.eclipse.jface.viewers.ISelection
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.custom.StyleRange
import org.eclipse.ui.{ IWorkbenchPart, ISelectionListener, IFileEditorInput }
import org.eclipse.ui.editors.text.{ ForwardingDocumentProvider, TextFileDocumentProvider }
import org.eclipse.ui.texteditor.{ IAbstractTextEditorHelpContextIds, ITextEditorActionConstants, IWorkbenchActionDefinitionIds, TextOperationAction }
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.tools.eclipse.javaelements.{ ScalaSourceFile, ScalaCompilationUnit }
import scala.tools.eclipse.markoccurrences.{ ScalaOccurrencesFinder, Occurrences }
import scala.tools.eclipse.semantichighlighting._
import scala.tools.eclipse.properties.ScalaSyntaxClass
import scala.tools.eclipse.util.SWTUtils._
import org.eclipse.jface.action.Action
import org.eclipse.jface.action.MenuManager
import org.eclipse.jface.action.IContributionItem
import org.eclipse.jface.action.Separator
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport
import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.swt.SWT

class ScalaSourceFileEditor extends CompilationUnitEditor with ScalaEditor {

  import ScalaSourceFileEditor._

  setPartName("Scala Editor")

  def scalaPrefStore = ScalaPlugin.plugin.getPreferenceStore
  def javaPrefStore = getPreferenceStore

  override protected def createActions() {
    super.createActions()

    val cutAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Cut.", this, ITextOperationTarget.CUT) //$NON-NLS-1$
    cutAction.setHelpContextId(IAbstractTextEditorHelpContextIds.CUT_ACTION)
    cutAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.CUT)
    setAction(ITextEditorActionConstants.CUT, cutAction)

    val copyAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Copy.", this, ITextOperationTarget.COPY, true) //$NON-NLS-1$
    copyAction.setHelpContextId(IAbstractTextEditorHelpContextIds.COPY_ACTION)
    copyAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.COPY)
    setAction(ITextEditorActionConstants.COPY, copyAction)

    val pasteAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Paste.", this, ITextOperationTarget.PASTE) //$NON-NLS-1$
    pasteAction.setHelpContextId(IAbstractTextEditorHelpContextIds.PASTE_ACTION)
    pasteAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.PASTE)
    setAction(ITextEditorActionConstants.PASTE, pasteAction)

    val selectionHistory = new SelectionHistory(this)

    val historyAction = new StructureSelectHistoryAction(this, selectionHistory)
    historyAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_LAST)
    setAction(StructureSelectionAction.HISTORY, historyAction)
    selectionHistory.setHistoryAction(historyAction)

    val selectEnclosingAction = new ScalaStructureSelectEnclosingAction(this, selectionHistory)
    selectEnclosingAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_ENCLOSING)
    setAction(StructureSelectionAction.ENCLOSING, selectEnclosingAction)

    val openAction = new Action {
      override def run {
        Option(getInputJavaElement) map (_.asInstanceOf[ScalaCompilationUnit]) foreach { scu =>
          scu.followReference(ScalaSourceFileEditor.this, getSelectionProvider.getSelection.asInstanceOf[ITextSelection])
        }
      }
    }
    openAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.OPEN_EDITOR)
    setAction("OpenEditor", openAction)
  }

  override protected def initializeKeyBindingScopes() {
    setKeyBindingScopes(Array(SCALA_EDITOR_SCOPE))
  }

  override def createJavaSourceViewerConfiguration: JavaSourceViewerConfiguration =
    new ScalaSourceViewerConfiguration(javaPrefStore, scalaPrefStore, this)

  override def setSourceViewerConfiguration(configuration: SourceViewerConfiguration) {
    super.setSourceViewerConfiguration(
      configuration match {
        case svc: ScalaSourceViewerConfiguration => svc
        case _ => new ScalaSourceViewerConfiguration(javaPrefStore, scalaPrefStore, this)
      })
  }

  private[eclipse] def sourceViewer = getSourceViewer

  private var occurrenceAnnotations: Array[Annotation] = _

  override def updateOccurrenceAnnotations(selection: ITextSelection, astRoot: CompilationUnit) {
    askForOccurrencesUpdate(selection, astRoot)
    super.updateOccurrenceAnnotations(selection, astRoot)
  }

  private def performOccurrencesUpdate(selection: ITextSelection, astRoot: CompilationUnit) {
    import ScalaPlugin.{ plugin => thePlugin }

    val documentProvider = getDocumentProvider
    if (documentProvider eq null)
      return

    //  TODO: find out why this code does a cast to IAdaptable before calling getAdapter 
    val adaptable = getEditorInput.asInstanceOf[IAdaptable].getAdapter(classOf[IJavaElement])
    // println("adaptable: " + adaptable.getClass + " : " + adaptable.toString)

    adaptable match {
      case scalaSourceFile: ScalaSourceFile =>
        val annotations = getAnnotations(selection, scalaSourceFile)
        val annotationModel = documentProvider.getAnnotationModel(getEditorInput)
        if (annotationModel eq null)
          return
        annotationModel.asInstanceOf[ISynchronizable].getLockObject() synchronized {
          val annotationModelExtension = annotationModel.asInstanceOf[IAnnotationModelExtension]
          annotationModelExtension.replaceAnnotations(occurrenceAnnotations, annotations)
          occurrenceAnnotations = annotations.keySet.toArray
        }

      case _ =>
        // TODO: pop up a dialog explaining what needs to be fixed or fix it ourselves
        thePlugin checkOrElse (adaptable.asInstanceOf[ScalaSourceFile], // trigger the exception, so as to get a diagnostic stack trace 
          "Could not recompute occurrence annotations: configuration problem")
    }
  }

  private def getAnnotations(selection: ITextSelection, scalaSourceFile: ScalaSourceFile): mutable.Map[Annotation, Position] = {
    val annotations = for {
      Occurrences(name, locations) <- new ScalaOccurrencesFinder(scalaSourceFile, selection.getOffset, selection.getLength).findOccurrences.toList
      location <- locations
      offset = location.getOffset
      length = location.getLength
      position = new Position(location.getOffset, location.getLength)
    } yield new Annotation(OCCURRENCE_ANNOTATION, false, "Occurrence of '" + name + "'") -> position
    mutable.Map(annotations: _*)
  }

  def askForOccurrencesUpdate(selection: ITextSelection, astRoot: CompilationUnit) {

    if (selection.getLength < 0 || selection.getOffset < 0) return

    import org.eclipse.core.runtime.jobs.Job
    import org.eclipse.core.runtime.IProgressMonitor
    import org.eclipse.core.runtime.{ IStatus, Status }

    val job = new Job("updateOccurrenceAnnotations") {
      def run(monitor: IProgressMonitor): IStatus = {
        performOccurrencesUpdate(selection, astRoot)
        Status.OK_STATUS
      }
    }

    job.setPriority(Job.INTERACTIVE)
    job.schedule()
  }

  override def doSelectionChanged(selection: ISelection) {
    super.doSelectionChanged(selection)
    val selectionProvider = getSelectionProvider
    if (selectionProvider != null)
      selectionProvider.getSelection match {
        case textSel: ITextSelection => askForOccurrencesUpdate(textSel, null)
        case _ =>
      }
  }

  lazy val selectionListener = new ISelectionListener() {
    def selectionChanged(part: IWorkbenchPart, selection: ISelection) {
      selection match {
        case textSel: ITextSelection => askForOccurrencesUpdate(textSel, null)
        case _ =>
      }
    }
  }

  override def installOccurrencesFinder(forceUpdate: Boolean) {
    super.installOccurrencesFinder(forceUpdate)
    getEditorSite.getPage.addPostSelectionListener(selectionListener)
  }

  override def uninstallOccurrencesFinder() {
    getEditorSite.getPage.removePostSelectionListener(selectionListener)
    super.uninstallOccurrencesFinder
  }

  private val preferenceListener: IPropertyChangeListener = handlePreferenceStoreChanged _

  scalaPrefStore.addPropertyChangeListener(preferenceListener)

  override def dispose() {
    super.dispose()
    scalaPrefStore.removePropertyChangeListener(preferenceListener)
  }

  override def editorContextMenuAboutToShow(menu: org.eclipse.jface.action.IMenuManager): Unit = {
    super.editorContextMenuAboutToShow(menu)

    def groupMenuItemsByGroupId(items: Seq[IContributionItem]) = {
      // the different groups (as indicated by separators) and 
      // contributions in a menu are originally just a flat list
      items.foldLeft(Nil: List[(String, List[IContributionItem])]) {

        // start a new group
        case (others, group: Separator) => (group.getId, Nil) :: others

        // append contribution to the current group
        case ((group, others) :: rest, element) => (group, element :: others) :: rest

        // the menu does not start with a group, this shouldn't happen, but if
        // it does we just skip this element, so it will stay in the menu.
        case (others, _) => others
      } toMap
    }

    def findJdtSourceMenuManager(items: Seq[IContributionItem]) = {
      items.collect {
        case mm: MenuManager if mm.getId == "org.eclipse.jdt.ui.source.menu" => mm
      }
    }

    findJdtSourceMenuManager(menu.getItems) foreach { mm =>

      val groups = groupMenuItemsByGroupId(mm.getItems)

      // these two contributions won't work on Scala files, so we remove them
      val blacklist = List("codeGroup", "importGroup")

      // and provide our own organize imports instead
      mm.appendToGroup("importGroup", new refactoring.OrganizeImportsAction { setText("Organize Imports") })

      blacklist.flatMap(groups.get).flatten.foreach(mm.remove)
    }

    refactoring.RefactoringMenu.fillContextMenu(menu, this)
  }

  override def createPartControl(parent: org.eclipse.swt.widgets.Composite) {
    super.createPartControl(parent)
    refactoring.RefactoringMenu.fillQuickMenu(this)
  }

  override def configureSourceViewerDecorationSupport(support: SourceViewerDecorationSupport) {
    super.configureSourceViewerDecorationSupport(support)
    for (symbolAnnotationInfo <- SymbolAnnotations.allSymbolAnnotations.values)
      support.setAnnotationPreference(symbolAnnotationInfo.annotationPreference)
  }

  override protected def getSourceViewerDecorationSupport(viewer: ISourceViewer): SourceViewerDecorationSupport = {
    if (fSourceViewerDecorationSupport == null) {
      fSourceViewerDecorationSupport = new ScalaSourceViewerDecorationSupport(viewer)
      configureSourceViewerDecorationSupport(fSourceViewerDecorationSupport)
    }
    fSourceViewerDecorationSupport
  }

  class ScalaSourceViewerDecorationSupport(viewer: ISourceViewer)
    extends SourceViewerDecorationSupport(viewer, getOverviewRuler, getAnnotationAccess, getSharedColors) {

    override protected def createAnnotationPainter(): AnnotationPainter = {
      val annotationPainter = super.createAnnotationPainter
      for (annotationInfo <- SymbolAnnotations.allSymbolAnnotations.values) {
        val syntaxClass = annotationInfo.syntaxClass
        annotationPainter.addTextStyleStrategy(syntaxClass.baseName, new ForegroundColourTextStyleStrategy(syntaxClass))
      }

      annotationPainter
    }

  }

  class ForegroundColourTextStyleStrategy(syntaxClass: ScalaSyntaxClass) extends AnnotationPainter.ITextStyleStrategy {

    def applyTextStyle(styleRange: StyleRange, annotationColor: Color) {
      syntaxClass.populateStyleRange(styleRange, scalaPrefStore)
    }

  }

}

object ScalaSourceFileEditor {

  private val EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS = "org.eclipse.ui.texteditor.ConstructedEditorMessages"
  private val bundleForConstructedKeys = ResourceBundle.getBundle(EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS)

  private val SCALA_EDITOR_SCOPE = "scala.tools.eclipse.scalaEditorScope"

  private val OCCURRENCE_ANNOTATION = "org.eclipse.jdt.ui.occurrences"
}
