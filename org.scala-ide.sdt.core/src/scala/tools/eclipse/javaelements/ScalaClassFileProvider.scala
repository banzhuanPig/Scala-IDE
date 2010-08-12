/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse.javaelements

import java.io.ByteArrayInputStream

import org.eclipse.jdt.internal.core.{ ClassFile, PackageFragment }

import scala.tools.eclipse.ScalaClassFileDescriber

class ScalaClassFileProvider extends scala.tools.eclipse.contribution.weaving.jdt.cfprovider.IClassFileProvider {
  override def create(contents : Array[Byte], parent : PackageFragment, name : String) : ClassFile =
    ScalaClassFileDescriber.isScala(new ByteArrayInputStream(contents)) match {
      case Some(sourceFile) =>
        val scf = new ScalaClassFile(parent, name, sourceFile)
        val sourceMapper = parent.getSourceMapper
        if (sourceMapper == null)
          null
        else {
          val source = sourceMapper.findSource(scf.getBinaryType, sourceFile)
          if (source != null) scf else null
        }
      case _ => null
    }
}
