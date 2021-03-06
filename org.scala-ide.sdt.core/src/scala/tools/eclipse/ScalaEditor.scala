/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import scala.tools.eclipse.lexical.ScalaPartitions
import org.eclipse.jdt.ui.text.IJavaPartitions
import scala.tools.eclipse.contribution.weaving.jdt.ui.javaeditor.IScalaEditor
import org.eclipse.jface.text.IDocumentPartitioner
import scala.tools.eclipse.lexical.ScalaDocumentPartitioner

trait ScalaEditor extends IScalaEditor {

  def createDocumentPartitioner(): IDocumentPartitioner = new ScalaDocumentPartitioner

}

object ScalaEditor {

  val LEGAL_CONTENT_TYPES = Array[String](
    IJavaPartitions.JAVA_DOC,
    IJavaPartitions.JAVA_MULTI_LINE_COMMENT,
    IJavaPartitions.JAVA_SINGLE_LINE_COMMENT,
    IJavaPartitions.JAVA_STRING,
    IJavaPartitions.JAVA_CHARACTER,
    ScalaPartitions.SCALA_MULTI_LINE_STRING)

}
