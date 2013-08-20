/*
 * Copyright 2012, 2013 Broad Institute, Inc.
 *
 * This file is part of Pilon.
 *
 * Pilon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * Pilon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pilon.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.broadinstitute.pilon

import java.io.{File,PrintWriter,FileWriter,BufferedWriter}
import scala.collection.JavaConversions._
import scala.collection.mutable.Map
import net.sf.picard.reference._

class GenomeFile(val referenceFile: File, val targets : String = "") {
	val referenceSequenceFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(referenceFile)
	val (contigs, contigMap) = {
	  var contigList = List[ReferenceSequence]()
	  var contigMap = Map.empty[String, ReferenceSequence]
	  var refseq = referenceSequenceFile.nextSequence()
	  while (refseq != null) {
	    contigMap(refseq.getName()) = refseq
	    contigList ::= refseq
	    refseq = referenceSequenceFile.nextSequence()
	  } 
	  (contigList reverse, contigMap)
	}

	val regions = {
	 if (targets != "") 
	   parseTargets(targets)
	 else 
	   (contigs map { c => (c.getName(), contigRegions(c)) } )
	}
	
	def getSequence(contig: String): ReferenceSequence = {
	  referenceSequenceFile.getSequence(contig)
	}
	
	def getSubsequenceAt(contig: String, start: Long, stop: Long): ReferenceSequence = {
	  referenceSequenceFile.getSubsequenceAt(contig, start, stop)
	}
	
	def contigRegions(contig: ReferenceSequence, maxSize: Int = 10000000) = {
	  val cLength = contig.length
	  val nChunks : Int = (cLength + maxSize - 1) / maxSize
	  val chunkSize : Int = (cLength + nChunks - 1) / nChunks
	  //println(contig.getName + ": " + cLength + " " + nChunks + " " + chunkSize + " " + (nChunks*chunkSize))
	  Range(1, cLength+1, chunkSize ) map { base => new GenomeRegion(contig,  base, cLength min base+chunkSize-1) }
	}
	 
	def referenceRegions(maxSize: Int = 10000000) = {
	  contigs flatMap { c => contigRegions(c, maxSize) }
	}
	
	
  def processBam(bam: BamFile) = regions foreach { _._2 foreach { _.processBam(bam) } }
  
  def validateBam(bamFile: BamFile) = {
    val seqs = bamFile.getSeqs
    require(!seqs.intersect(contigMap.keySet).isEmpty, bamFile + " doesn't match " + referenceFile)
  }
  
  def writeFastaElement(writer: PrintWriter, header: String, sequence: String) = {
    writer.println(">" + header)
    sequence sliding(80, 80) foreach writer.println
  }

  def processRegions(bamFiles: List[BamFile]) = {
    bamFiles foreach validateBam

    if (Pilon.strays)
      for (bam <- bamFiles)
        if (bam.bamType != 'unpaired)
          bam.buildStrayMateMap

    val changesFile = Pilon.outputFile(".changes")
    val changesWriter = if (Pilon.changes)
	                      new PrintWriter(new BufferedWriter(new FileWriter(changesFile)))
                      else null
    val fastaFile = Pilon.outputFile(".fasta")
    val fastaWriter = if (!Pilon.fixList.isEmpty)
	                      new PrintWriter(new BufferedWriter(new FileWriter(fastaFile)))
                      else null

    val vcf: Vcf = if (Pilon.vcf)
	                   new Vcf(Pilon.outputFile(".vcf"), regions map {r => (r._1, r._2 map {_.size} sum)})
                   else null

    regions foreach { reg =>
      val name = reg._1
      val sep = if (name.indexOf("|") < 0) "_"
      else if (name(name.length-1) == '|') ""
      else "|"
      val newName = name + sep + "pilon"
      reg._2 foreach { r =>
        println("Processing " + r)
        r.initializePileUps
        bamFiles foreach { r.processBam(_) }
        r.postProcess
        if (Pilon.vcf || !Pilon.fixList.isEmpty) {
          r.identifyAndFixIssues
        }
        if (Pilon.vcf) {
          println("Writing " + r.name + " VCF to " + vcf.file)
          r.writeVcf(vcf)
        }
        if (Pilon.changes) {
          println("Writing " + r.name + " changes to " + changesFile)
          r.writeChanges(changesWriter, newName)

        }
        r.finalizePileUps
      }
      if (!Pilon.fixList.isEmpty) {

        println("Writing updated " + newName + " to " + fastaFile)
        val fixedRegions = reg._2 map { _.bases }
        val bases = fixedRegions reduceLeft {_ ++ _} map {_.toChar} mkString ""
        writeFastaElement(fastaWriter, newName, bases)
        //TODO: write out change file
      }
    }

    if (Pilon.fixList contains 'novel) {
      val novelContigs = assembleNovel(bamFiles)
      for (n <- 0 until novelContigs.length) {
        val header = "pilon_novel_%03d".format(n + 1)
        println("Appending " + header + " length " + novelContigs(n).length)
        writeFastaElement(fastaWriter, header, novelContigs(n))
      }
    }
    if (!Pilon.fixList.isEmpty) fastaWriter.close
    if (Pilon.vcf) vcf.close
    if (Pilon.changes) changesWriter.close
  }

  def identifyAndFixIssues() = regions foreach { _._2 foreach { _.identifyAndFixIssues } }
  
  def assembleNovel(bamFiles: List[BamFile]) = {
    print("Assembling novel sequence:")
    val genomeGraph = new Assembler()
    print(" graphing genome")
    for (contig <- contigMap.values) {
      genomeGraph.addSeq(GenomeRegion.baseString(contig.getBases))
      if (Pilon.verbose) print("..." + contig.getName)
    }
    if (Pilon.verbose) println
    val assembler = new Assembler()
    bamFiles filter {_.bamType != 'jumps} foreach { bam =>
      val reads = bam.getUnaligned
      print("..." + reads.length + " unmapped reads")
      assembler.addReads(reads)
    }
    print("...assembling contigs")
    val contigs = assembler.novel(genomeGraph)
    println
    val contigLengths = contigs map {_.length}
    println("Assembled %d novel contigs containing %d bases".format(contigs.length, contigLengths.sum))
    contigs
  }

  def parseTargets(targetString: String) = {
    val targetHelp = "Target string must be of the form \"element:start-stop\""
    val targets = for (target <- targetString.split(",")) yield {
      val t1 = target.split(":")
      require(t1.size <= 2, targetHelp)
      val contig = contigMap(t1(0))
      val region = 
        if (t1.size == 1) {
            new GenomeRegion(contig, 1, contig.length)
        } else {
        	val t2 = t1(1).split("-")
        	require(t2.size <= 2, targetHelp)
        	new GenomeRegion(contig, t2(0).toInt, t2(1).toInt)
        }
      println("Target: " + region)
      (contig.getName, List(region))
    }
    targets.toList
  }
  
  override def toString() = referenceFile.getCanonicalPath()
}
