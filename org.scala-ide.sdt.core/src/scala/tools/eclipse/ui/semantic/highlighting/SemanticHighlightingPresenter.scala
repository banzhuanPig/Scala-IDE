/**
 * 
 */
package scala.tools.eclipse.ui.semantic.highlighting

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor;
import org.eclipse.jdt.internal.ui.JavaPlugin;

import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.preference.PreferenceConverter

import org.eclipse.jface.text.source.ISourceViewer;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.{IAnnotationAccess,AnnotationPainter,IAnnotationModelExtension};

import org.eclipse.swt.SWT

import scala.tools.eclipse.javaelements.ScalaCompilationUnit;
import scala.tools.nsc.util.RangePosition
import scala.tools.eclipse.util.ColorManager

import scala.collection._

/**
 * @author Jin Mingjian
 *
 */
class SemanticHighlightingPresenter(fEditor: CompilationUnitEditor, fSourceViewer: ISourceViewer) extends IDocumentListener {
 
  var currentAnnotations: Array[Annotation] = _
	
  val annotationAccess = new IAnnotationAccess() {
    def getType(annotation: Annotation) = annotation.getType();
    def isMultiLine(annotation: Annotation) = true 
    def isTemporary(annotation: Annotation) = true
  };
  val painter = new AnnotationPainter(fSourceViewer, annotationAccess);
  import scala.tools.eclipse.ui.preferences.ScalaEditorColoringPreferencePage._
  val fUnderlineStyle = getPreferenceStore().getInt(P_UNDERLINE) match {
	  case 1 => SWT.UNDERLINE_SQUIGGLE
	  case 2 => SWT.UNDERLINE_DOUBLE
	  case 3 => SWT.UNDERLINE_SINGLE
	  case 4 => 8 // for no underline case
  }
  val fFontStyle1 = getPreferenceStore().getBoolean(P_BLOD) match { 
	  case true => SWT.BOLD
	  case _ => SWT.NORMAL
  }
  val fFontStyle2 = getPreferenceStore().getBoolean(P_ITALIC) match { 
	  case true => SWT.ITALIC
	  case _ => SWT.NORMAL
  }
  val fColorValue = PreferenceConverter.getColor(getPreferenceStore(),P_COLOR)
  painter.addTextStyleStrategy(ImplicitConversionsOrArgsAnnotation.KIND, 
		  new ImplicitConversionsOrArgsDrawingStrategy(fUnderlineStyle,fFontStyle1 | fFontStyle2) );
  painter.addAnnotationType(ImplicitConversionsOrArgsAnnotation.KIND, ImplicitConversionsOrArgsAnnotation.KIND);
  painter.setAnnotationTypeColor(ImplicitConversionsOrArgsAnnotation.KIND, 
		  ColorManager.getDefault.getColor(fColorValue))
  fSourceViewer.asInstanceOf[org.eclipse.jface.text.TextViewer].addPainter(painter);
  fSourceViewer.asInstanceOf[org.eclipse.jface.text.TextViewer].addTextPresentationListener(painter);
 
	
	/**
	 * The manipulation described by the document event will be performed.
	 *
	 * @param event the document event describing the document change
	 */
	override def documentAboutToBeChanged(event: DocumentEvent){}

	/**
	 * The manipulation described by the document event has been performed.
	 *
	 * @param event the document event describing the document change
	 */
	override def documentChanged(event: DocumentEvent) {
		update()
		}
	
	
	def update() { 
		val cu = JavaPlugin.getDefault().getWorkingCopyManager().getWorkingCopy(fEditor.getEditorInput());
	    val scu = cu.asInstanceOf[ScalaCompilationUnit] 
	    scu.withCompilerResult({ crh =>
          import crh._
          
          val toAdds = new java.util.HashMap[Annotation,org.eclipse.jface.text.Position]
          var toAddAnnotations = List[Annotation]()
           object viewsCollector extends compiler.Traverser {
            override def traverse(t: compiler.Tree): Unit = t match {
              case v: compiler.ApplyImplicitView =>
                val ia = new ImplicitConversionsOrArgsAnnotation(ImplicitConversionsOrArgsAnnotation.KIND, 
                		false,
                		v.fun.toString)
                val pos = new org.eclipse.jface.text.Position(v.pos.start, v.pos.end-v.pos.start);
                toAdds.put(ia,pos)
                toAddAnnotations = ia::toAddAnnotations
                println(v)
                super.traverse(t)
              case _ =>
                super.traverse(t)
            }
          }
          viewsCollector.traverse(body)
          
          fSourceViewer.getAnnotationModel().asInstanceOf[IAnnotationModelExtension].replaceAnnotations(currentAnnotations,toAdds);
          currentAnnotations = toAddAnnotations.toArray
        })
	}
	
	def getPreferenceStore(): IPreferenceStore = {
		scala.tools.eclipse.ScalaPlugin.plugin.getPreferenceStore()
	}
 
}