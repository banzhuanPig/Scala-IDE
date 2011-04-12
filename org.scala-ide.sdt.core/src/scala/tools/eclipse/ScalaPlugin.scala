/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import org.eclipse.jdt.core.IJavaProject
import scala.collection.mutable.HashMap
import scala.util.control.ControlThrowable

import org.eclipse.core.resources.{ IFile, IProject, IResourceChangeEvent, IResourceChangeListener, ResourcesPlugin }
import org.eclipse.core.runtime.{ CoreException, FileLocator, IStatus, Platform, Status }
import org.eclipse.core.runtime.content.IContentTypeSettings
import org.eclipse.jdt.core.{ ElementChangedEvent, IElementChangedListener, JavaCore, IJavaElement, IJavaElementDelta, IPackageFragmentRoot }
import org.eclipse.jdt.internal.core.{ JavaModel, JavaProject, PackageFragment, PackageFragmentRoot }
import org.eclipse.jdt.internal.core.util.Util
import org.eclipse.jdt.internal.ui.javaeditor.IClassFileEditorInput
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.graphics.Color
import org.eclipse.ui.{ IEditorInput, IFileEditorInput, PlatformUI, IPartListener, IWorkbenchPart, IWorkbenchPage, IPageListener, IEditorPart }
import org.eclipse.ui.part.FileEditorInput
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.osgi.framework.BundleContext

import scala.tools.eclipse.javaelements.{ ScalaElement, ScalaSourceFile }
import scala.tools.eclipse.util.OSGiUtils.pathInBundle
import scala.tools.eclipse.templates.ScalaTemplateManager

object ScalaPlugin {
  var plugin: ScalaPlugin = _

  /** Returns the active workbench shell, or null if one does not exist */
  def getShell: Shell = {
    val workbench = PlatformUI.getWorkbench
    var window =
      Option(workbench.getActiveWorkbenchWindow) orElse workbench.getWorkbenchWindows.headOption
    window.map { _.getShell }.orNull
  }
}

class ScalaPlugin extends AbstractUIPlugin with IResourceChangeListener with IElementChangedListener with IPartListener {
  ScalaPlugin.plugin = this

  def pluginId = "org.scala-ide.sdt.core"
  def compilerPluginId = "org.scala-ide.scala.compiler"
  def libraryPluginId = "org.scala-ide.scala.library"

  def wizardPath = pluginId + ".wizards"
  def wizardId(name: String) = wizardPath + ".new" + name
  def classWizId = wizardId("Class")
  def traitWizId = wizardId("Trait")
  def objectWizId = wizardId("Object")
  def applicationWizId = wizardId("Application")
  def projectWizId = wizardId("Project")
  def netProjectWizId = wizardId("NetProject")

  def editorId = "scala.tools.eclipse.ScalaSourceFileEditor"
  def builderId = pluginId + ".scalabuilder"
  def natureId = pluginId + ".scalanature"
  def launchId = "org.scala-ide.sdt.launching"
  val scalaCompiler = "SCALA_COMPILER_CONTAINER"
  val scalaLib = "SCALA_CONTAINER"
  def scalaCompilerId = launchId + "." + scalaCompiler
  def scalaLibId = launchId + "." + scalaLib
  def launchTypeId = "scala.application"
  def problemMarkerId = pluginId + ".problem"

  // Retained for backwards compatibility
  val oldPluginId = "ch.epfl.lamp.sdt.core"
  val oldLibraryPluginId = "scala.library"
  val oldNatureId = oldPluginId + ".scalanature"
  val oldBuilderId = oldPluginId + ".scalabuilder"
  val oldLaunchId = "ch.epfl.lamp.sdt.launching"
  val oldScalaLibId = oldLaunchId + "." + scalaLib

  val scalaFileExtn = ".scala"
  val javaFileExtn = ".java"
  val jarFileExtn = ".jar"

  val scalaCompilerBundle = Platform.getBundle(ScalaPlugin.plugin.compilerPluginId)
  val compilerClasses = pathInBundle(scalaCompilerBundle, "/lib/scala-compiler.jar")
  val continuationsClasses = pathInBundle(scalaCompilerBundle, "/lib/continuations.jar")
  val compilerSources = pathInBundle(scalaCompilerBundle, "/lib/scala-compiler-src.jar")

  val scalaLibBundle = Platform.getBundle(ScalaPlugin.plugin.libraryPluginId)
  val libClasses = pathInBundle(scalaLibBundle, "/lib/scala-library.jar")
  val libSources = pathInBundle(scalaLibBundle, "/lib/scala-library-src.jar")
  val dbcClasses = pathInBundle(scalaLibBundle, "/lib/scala-dbc.jar")
  val dbcSources = pathInBundle(scalaLibBundle, "/lib/scala-dbc-src.jar")
  val swingClasses = pathInBundle(scalaLibBundle, "/lib/scala-swing.jar")
  val swingSources = pathInBundle(scalaLibBundle, "/lib/scala-swing-src.jar")

  lazy val templateManager = new ScalaTemplateManager()

  val pageListener = new IPageListener {
    def pageOpened(page: IWorkbenchPage) {
      page.addPartListener(ScalaPlugin.this)
    }

    def pageClosed(page: IWorkbenchPage) {
      page.removePartListener(ScalaPlugin.this)
    }

    def pageActivated(page: IWorkbenchPage) {}
  }

  private val projects = new HashMap[IProject, ScalaProject]

  override def start(context: BundleContext) = {
    super.start(context)

    ResourcesPlugin.getWorkspace.addResourceChangeListener(this, IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.POST_CHANGE)
    JavaCore.addElementChangedListener(this)
    PlatformUI.getWorkbench.getEditorRegistry.setDefaultEditor("*.scala", editorId)
    PlatformUI.getWorkbench.getActiveWorkbenchWindow.addPageListener(pageListener)

    println("Scala compiler bundle: " + scalaCompilerBundle.getLocation)
    PerspectiveFactory.updatePerspective
    diagnostic.StartupDiagnostics.run
  }

  override def stop(context: BundleContext) = {
    ResourcesPlugin.getWorkspace.removeResourceChangeListener(this)
    Option(PlatformUI.getWorkbench.getActiveWorkbenchWindow).map(_.removePageListener(pageListener))
    super.stop(context)
  }

  def workspaceRoot = ResourcesPlugin.getWorkspace.getRoot

  def getJavaProject(project: IProject) = JavaCore.create(project)

  def getScalaProject(project: IProject): ScalaProject = projects.synchronized {
    projects.get(project) match {
      case Some(scalaProject) => scalaProject
      case None =>
        val scalaProject = new ScalaProject(project)
        projects(project) = scalaProject
        scalaProject
    }
  }

  def getScalaProject(input: IEditorInput): ScalaProject = input match {
    case fei: IFileEditorInput       => getScalaProject(fei.getFile.getProject)
    case cfei: IClassFileEditorInput => getScalaProject(cfei.getClassFile.getJavaProject.getProject)
    case _                           => null
  }

  def isScalaProject(project: IJavaProject): Boolean = isScalaProject(project.getProject)

  def isScalaProject(project: IProject): Boolean =
    try {
      project != null && project.isOpen && (project.hasNature(natureId) || project.hasNature(oldNatureId))
    } catch {
      case _: CoreException => false
    }

  override def resourceChanged(event: IResourceChangeEvent) {
    (event.getResource, event.getType) match {
      case (project: IProject, IResourceChangeEvent.PRE_CLOSE) =>
        projects.synchronized {
          projects.get(project) match {
            case Some(scalaProject) =>
              projects.remove(project)
              println("resetting compilers for " + project.getName)
              scalaProject.resetCompilers
            case None =>
          }
        }
      case _ =>
    }
  }

  override def elementChanged(event: ElementChangedEvent) {
    findRemovedSources(event.getDelta)
  }

  private def findRemovedSources(delta: IJavaElementDelta) {
    import IJavaElement._
    import IJavaElementDelta._

    val isChanged = delta.getKind == CHANGED
    val isRemoved = delta.getKind == REMOVED
    def hasFlag(flag: Int) = (delta.getFlags & flag) != 0

    val elem = delta.getElement
    val processChildren: Boolean = elem.getElementType match {
      case JAVA_MODEL                                       => true
      case JAVA_PROJECT if !isRemoved && !hasFlag(F_CLOSED) => true

      case PACKAGE_FRAGMENT_ROOT =>
        if (isRemoved || hasFlag(F_REMOVED_FROM_CLASSPATH | F_ADDED_TO_CLASSPATH | F_ARCHIVE_CONTENT_CHANGED)) {
          println("package fragment root changed (resetting pres compiler): " + elem)
          getScalaProject(elem.getJavaProject.getProject).resetPresentationCompiler
          false
        } else true

      case PACKAGE_FRAGMENT => true

      case COMPILATION_UNIT if elem.isInstanceOf[ScalaSourceFile] && isRemoved =>
        val project = elem.getJavaProject.getProject
        if (project.isOpen) {
          getScalaProject(project).
            doWithPresentationCompiler { _.discardSourceFile(elem.asInstanceOf[ScalaSourceFile]) }
        }
        false

      case _ => false
    }

    if (processChildren)
      delta.getAffectedChildren foreach { findRemovedSources(_) }
  }

  def logWarning(msg: String): Unit = getLog.log(new Status(IStatus.WARNING, pluginId, msg))

  def logError(t: Throwable): Unit = logError(t.getClass + ":" + t.getMessage, t)

  def logError(msg: String, t: Throwable): Unit = {
    val t1 = if (t != null) t else { val ex = new Exception; ex.fillInStackTrace; ex }
    val status1 = new Status(IStatus.ERROR, pluginId, IStatus.ERROR, msg, t1)
    getLog.log(status1)

    val status = t match {
      case ce: ControlThrowable =>
        val t2 = { val ex = new Exception; ex.fillInStackTrace; ex }
        val status2 = new Status(
          IStatus.ERROR, pluginId, IStatus.ERROR,
          "Incorrectly logged ControlThrowable: " + ce.getClass.getSimpleName + "(" + ce.getMessage + ")", t2)
        getLog.log(status2)
      case _ =>
    }
  }

  def bundlePath = check {
    val bundle = getBundle
    val bpath = bundle.getEntry("/")
    val rpath = FileLocator.resolve(bpath)
    rpath.getPath
  }.getOrElse("unresolved")

  final def check[T](f: => T) =
    try {
      Some(f)
    } catch {
      case e: Throwable =>
        logError(e)
        None
    }

  final def checkOrElse[T](f: => T, msgIfError: String): Option[T] = {
    try {
      Some(f)
    } catch {
      case e: Throwable =>
        logError(msgIfError, e)
        None
    }
  }

  def isBuildable(file: IFile) = (file.getName.endsWith(scalaFileExtn) || file.getName.endsWith(javaFileExtn))

  // IPartListener
  def partActivated(part: IWorkbenchPart) {}
  def partDeactivated(part: IWorkbenchPart) {}
  def partBroughtToTop(part: IWorkbenchPart) {}
  def partOpened(part: IWorkbenchPart) {
    doWithCompilerAndFile(part) { (compiler, ssf) =>
      compiler.askReload(ssf, ssf.getContents)
    }
  }
  def partClosed(part: IWorkbenchPart) {
    doWithCompilerAndFile(part) { (compiler, ssf) =>
      compiler.discardSourceFile(ssf)
    }
  }

  private def doWithCompilerAndFile(part: IWorkbenchPart)(op: (ScalaPresentationCompiler, ScalaSourceFile) => Unit) {
    part match {
      case editor: IEditorPart =>
        editor.getEditorInput match {
          case fei: FileEditorInput =>
            val f = fei.getFile
            if (f.getName.endsWith(scalaFileExtn)) {
              for (ssf <- ScalaSourceFile.createFromPath(f.getFullPath.toString)) {
                val proj = getScalaProject(f.getProject)
                proj.doWithPresentationCompiler(op(_, ssf)) // so that an exception is not thrown
              }
            }
          case _ =>
        }
      case _ =>
    }
  }
}
