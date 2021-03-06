/**
 * Softmax multiclass logistic regressor
 * @author cdgore
 * 2013-07-01
 * Refactored 2013-10-09
 */
package com.cdgore.mlspark

import java.io._
import java.util.Random
import java.util.Properties

import org.apache.spark
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.util.Vector

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.IntWritable
import org.apache.hadoop.io.Writable
import org.apache.hadoop.io.Text

import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;

//import org.apache.mahout.math.Vector
import org.apache.mahout.math.VectorWritable
import org.apache.mahout.math.DenseVector

import org.jblas.DoubleMatrix
import org.jblas.MatrixFunctions

/**
 * @author cdgore
 *
 */

object SoftmaxLR extends Serializable {
  //  val R = 1000     // Scaling factor
  //  val rand = new Random(42)

  def parseStringToStringDoubleMatrix(line: String, classIndex: Int, vectorStartIndex: Int): (String, DoubleMatrix) = {
    return (line.split('\t')(classIndex), new DoubleMatrix(line.toString().split('\t').slice(vectorStartIndex, line.length).map(_.toDouble)))
  }
  
  def VectorWritableToJBlasDoubleMatrix(line: VectorWritable): DoubleMatrix = {
    return new DoubleMatrix(line.get.all.asScala.map(_.get).toArray)
  }
  
  def JBlasDoubleMatrixToVectorWritable(v: DoubleMatrix): VectorWritable = {
    return new VectorWritable(new DenseVector(v.toArray()))
  }
//  
//  def parseVector(key: IntWritable, line: Text): Vector = {
//    return new Vector(line.toString().split('\t').map(_.toDouble))
//  }
//  
//  def parseTextToVector(line: Text): Vector = {
//    return new Vector(line.toString().split('\t').map(_.toDouble))
//  }
//  
//  def parseTextToDoubleMatrix(line: Text): DoubleMatrix = {
//    return new DoubleMatrix(line.toString().split('\t').map(_.toDouble))
//  }
//
//  def parseIntDoubleMatrix(key: IntWritable, line: Text): (Int, DoubleMatrix) = {
//    return (key.get(), new DoubleMatrix(line.toString().split('\t').map(_.toDouble)))
//  }
//  
//  def parseTextDoubleMatrix(key: Text, line: Text): (Text, DoubleMatrix) = {
//    return (key, new DoubleMatrix(line.toString().split('\t').map(_.toDouble)))
//  }
  
  // Naive L1 penalty
  def l1Update(subGradient1: DoubleMatrix, w: DoubleMatrix, reg: Double, lr: Double): DoubleMatrix = {
    return w.add(subGradient1.sub(MatrixFunctions.signum(w).mul(reg)).mul(lr))
  }
  
  // L1 penalty with "clipping"
  def l1ClippingUpdate(subGradient1: DoubleMatrix, w: DoubleMatrix, reg: Double, lr: Double): DoubleMatrix = {
    return new DoubleMatrix(w.add(subGradient1.mul(lr)).toArray.map(x => x match {
      case k if k > 0 => math.max(0, k - (reg * lr))
      case k if k < 0 => math.min(0, k + (reg * lr))
      case _ => 0
    }))
  }
  
  def l2Update(subGradient1: DoubleMatrix, w: DoubleMatrix, reg: Double, lr: Double): DoubleMatrix = {
    return w.add(subGradient1.sub(w.mul(2 * reg)).mul(lr))
  }
  
  def simpleUpdate(subGradient1: DoubleMatrix, w: DoubleMatrix, reg: Double, lr: Double): DoubleMatrix = {
    return w.add(subGradient1.mul(lr))
  }

  def calculateLRGradient(tC: String, x: DoubleMatrix, weights: Array[(String, DoubleMatrix)]): Iterator[(String, DoubleMatrix)] = {
    val catIDTargetExpTransW = weights.map {
      case (wC, w) => (wC, tC equals wC match {
        case a if a => 1
        case a if !a => 0
      }, math.exp(w.dot(x)), w)// Numerator of softmax function: exp(wTx)
    }
    // Denominator of softmax function: Sum(exp(wTx))
    val summedExpTrans = catIDTargetExpTransW.map{case (a, b, c, d) => c}.sum
    return catIDTargetExpTransW.map {
      case (wC, y, expTrans, w) => (wC, x.mul(y - (expTrans / summedExpTrans)))
//      case (wC, y, expTrans, w) => (wC, regUpdate(x.mul(y - (expTrans / summedExpTrans)), w, reg/numSamples, lr))
//      regUpdate: (DoubleMatrix, DoubleMatrix, Double, Double)  => DoubleMatrix = simpleUpdate
//      lr: Double, reg: Double, numSamples: Double
    }.seq
  }
  
  def calculateLoss(tC: String, x: DoubleMatrix, weights: Array[(String, DoubleMatrix)]): Iterator[(String, Double)] = {
    val catIDTargetExpTransW = weights.map {
      case (wC, w) => (wC, tC equals wC match {
        case a if a => 1
        case a if !a => 0
      }, math.exp(w.dot(x)))
    }
    val summedExpTrans = catIDTargetExpTransW.map{case (a, b, c) => c}.sum
    return catIDTargetExpTransW.map {
      case (wC, y, expTrans) => (wC, y * math.log(expTrans / summedExpTrans))
    }.seq
  }
  
  def predictSoftmax(rowID: String, x: DoubleMatrix, weights: Array[(String, DoubleMatrix)], tao: Double): Iterator[(String, String, Double)] = {
    val catIDTargetExpTransW = weights.map {
      case (wC, w) => (wC, math.exp(w.dot(x) / tao))
    }
    var summedExpTrans = 0.0
    for (eT <- catIDTargetExpTransW)
      summedExpTrans += eT._2
    return catIDTargetExpTransW.map {
      case (wC, expTrans) => (rowID, wC, (expTrans / summedExpTrans))
    }.seq
  }
  
//  def predictLR(userID: Int, x: DoubleMatrix, weights: Array[(String, DoubleMatrix)]): Iterator[(Int, (String, Double))] = {
//    val catIDExpTransW = weights.map {
//      case (wC, w) => (wC, math.exp(w.dot(x)), w)
//    }
//    var summedExpTrans = 0.0
//    for (eT <- catIDExpTransW)
//      summedExpTrans += eT._2
//    return catIDExpTransW.map {
//      case (wC, expTrans, w) => (userID, (wC, expTrans / summedExpTrans ))
//    }.seq
//  }

  def trainLR (sc: spark.SparkContext, data: spark.rdd.RDD[(String, DoubleMatrix)], learningRateAlpha: Double,
      regLambda: Double, regUpdate: (DoubleMatrix, DoubleMatrix, Double, Double) => DoubleMatrix = simpleUpdate, miniBatchTrainingPercentage: Double,
      maxIterations: Int, lossFile: String): spark.rdd.RDD[(String, org.jblas.DoubleMatrix)] = {
    // Initialize weight vector
//    var W = sc.broadcast(categories.map{ case x => (x, DoubleMatrix.randn(numClusters)) })
    val discountClass = data.map { case (c, u) => c }.distinct.collect
    val numFeatures = data.first._2.length
    var W = discountClass.map{ case x => (x, DoubleMatrix.randn(numFeatures)) }
    
    // Get the number of samples
//    val numSamples = data.count * miniBatchTrainingPercentage
    
    // Keep track of loss over training
    var sgdLossList = List[(Int, Double)]()
    var iterationNumber = 0

	for (i <- 1 to maxIterations) {
	  iterationNumber += 1
	  println("Iteration number: " + iterationNumber + "/" + maxIterations)
      
	  // Calculate gradient for each category for each user
      W = data.flatMap {
    	case (targetCat, x) => calculateLRGradient(targetCat, x, W)
      }.map {
        case (k, v) => (k, (v, 1))
      }.sample(false, miniBatchTrainingPercentage, 43).reduceByKey {
        case ((a1, b1), (a2, b2)) => (a1.add(a2), b1 + b2)
      }.map {
        case (c, (w, n)) => (c, w.div(n))
      }.join(sc.parallelize(W)).map{
        case (k, (v1, v2)) => (k, regUpdate(v1, v2, regLambda, learningRateAlpha))
      }.collect()
	  
      // Log SGD error
      if (lossFile != null) {
        val sgdLossTmp = data.flatMap {
          case (targetCat, x) => calculateLoss(targetCat, x, W)
        }.map {
          case (k, v) => (v, 1)
        }.reduce {
          case ((v1, n1), (v2, n2)) => (v1 + v2, n1 + n2)
        }
        val sgdLoss = -1 * sgdLossTmp._1 / sgdLossTmp._2.toDouble        
        println("Iteration: " + iterationNumber + " Loss: " + sgdLoss)
        sgdLossList = (iterationNumber, sgdLoss) :: sgdLossList
      }
    }

    if (lossFile != null) {
      val sgdE = sc.parallelize(sgdLossList.reverse)
      sgdE.saveAsTextFile(lossFile)
    }
    return sc.parallelize(W)//.saveAsSequenceFile(outputFile)
  }

//  override def main(args: Array[String]) {
  def main(args: Array[String]) {
    var master = "local[2]"
    if (System.getProperty("spark.MASTER") != null)
      master = System.getProperty("spark.MASTER")
    else if (System.getProperty("sparkEnvPropertiesFile") != null) {
      val sparkEnvProps = new Properties()
      try {
        sparkEnvProps.load(new FileInputStream(System.getProperty("sparkEnvPropertiesFile")))
        if (sparkEnvProps.getProperty("spark.MASTER") != null)
          master = sparkEnvProps.getProperty("spark.MASTER")
        else
          System.err.println("ERROR: Unable to read property 'spark.MASTER'")
      } catch {
        case e: Exception => println(e)
      }
    }
    val jars = System.getProperty("jarPath") match {
      case x:String => x.split(',').toList
      case _ => List()
    }
    val sc = new SparkContext(master,"Softmax regression", System.getProperty("spark.home"),
        jars)
    val conf = new Configuration()
    if (System.getenv("HADOOP_HOME") != null) {
      try {
        conf.addResource(new Path(System.getenv("HADOOP_HOME") + "/conf/core-site.xml"))
      } catch {
        case e: Exception => println(e)
      }
    } else if (System.getProperty("hadoopConfFile") != null)
      conf.addResource(new Path(System.getProperty("hadoopConfFile")))
    else
      System.err.println("WARNING: Cannot find core-site.xml and property 'hadoopConfFile' not specified")
    try {
      sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", conf.get("fs.s3n.awsAccessKeyId"))
      sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", conf.get("fs.s3n.awsSecretAccessKey"))
    } catch {
      case e: Exception => println(e)
    }
    if (System.getProperty("fs.s3n.awsAccessKeyId") != null)
      sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", System.getProperty("fs.s3n.awsAccessKeyId"))
    if (System.getProperty("fs.s3n.awsSecretAccessKey") != null)
      sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", System.getProperty("fs.s3n.awsSecretAccessKey"))
    if (System.getProperty("inputFeatureFile") == null || System.getProperty("inputClassFile") == null ||
        System.getProperty("inputTargetFile") == null || System.getProperty("parametersOutputFile") == null ||
        System.getProperty("maxIterations") == null || System.getProperty("learningRateAlpha") == null || 
        System.getProperty("regLambda") == null)
      throw new IOException("ERROR: Must specify properties 'inputFeatureFile', 'inputClassFile', 'inputTargetFile', 'parametersOutputFile', 'maxIterations', 'learningRateAlpha', and 'regLambda'")
    System.getProperties().list(System.out)
    
    // Get model parameters from system properties
    val learningRateAlpha = System.getProperty("learningRateAlpha").toDouble//0.44
    val regLambda = System.getProperty("regLambda").toDouble//0.03
	val maxIterations = System.getProperty("maxIterations").toInt//300
    val inputFeatureFile = System.getProperty("inputFeatureFile")
    val inputClassFile = System.getProperty("inputClassFile")
    val inputTargetFile = System.getProperty("inputTargetFile")
    val parameterFile = System.getProperty("parametersOutputFile")
    val miniBatchTrainingPercentage = System.getProperty("miniBatchTrainingPercentage") match {
      case null => 1.0
      case x => x.toDouble
    }
    val regularizationPrior = System.getProperty("regularization") match {
      case "l1" => l1Update _
      case "l1clipping" => l1ClippingUpdate _
      case "l2" => l2Update _
      case _ => simpleUpdate _
    }
    
    val lossFile = System.getProperty("lossFile")
    val offer_id_incentive_class = sc.sequenceFile[IntWritable, Text](inputClassFile).map {
      case(k, v) => (k.get, v.toString)}
      
    val data = sc.sequenceFile[IntWritable, VectorWritable](inputFeatureFile).map {
      case(k, v) => (k.get, VectorWritableToJBlasDoubleMatrix(v))
      }.join(offer_id_incentive_class).map {
        case(k, (v1, v2)) => (v2, v1)
        }.persist(spark.storage.StorageLevel.MEMORY_AND_DISK_SER)
    
    val weights = trainLR (sc, data, learningRateAlpha, regLambda, regularizationPrior, miniBatchTrainingPercentage, maxIterations, lossFile)
    
    // Write weights to file
    weights.map{
      case(k, v) => (new Text(k), new VectorWritable(new DenseVector(v.toArray())))
    }.saveAsSequenceFile(parameterFile)
    
    System.exit(0)
  }
}
