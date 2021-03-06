//This code was originally based on a code snippet from: 
//https://spark.apache.org/docs/1.1.0/mllib-linear-methods.html#linear-least-squares-lasso-and-ridge-regression
//The following approach to cross-validation was used:
//http://apache-spark-user-list.1001560.n3.nabble.com/How-to-use-K-fold-validation-in-spark-1-0-td8142.html

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.evaluation.RegressionMetrics
import org.apache.spark.mllib.regression.LinearRegressionModel
import org.apache.spark.mllib.regression.LassoWithSGD
import org.apache.spark.mllib.util.MLUtils
import java.io._
import scala.io.Source
import scala.collection.mutable.Map

//TODO Define the path to your data:
//var path = "/home/.../data/"

val numIterations = 500

//Check if a input line represents the header of the input file.
def isHeader(line: String) = line.contains("output");

//Runs Lasso (L1) regression for a given inputfile with the given path and number of iterations.
//The optimal regularization parameter lambda is chosen from a grid (3, 1, 0.5, 0.1, 0.01) using 5-fold cross-validation.
def runLasso(fileName: String, path: String, numIterations: Int) = {
	var fileNameCleaned = fileName.replaceAll(" ", "").replaceAll("\r", "").replaceAll("\n", "")
	val data = sc.textFile("file://" + path + fileNameCleaned).filter(!isHeader(_))
	val parsedData = data.map { line =>
		val parts = line.split(';')
		LabeledPoint(parts(0).toDouble, Vectors.dense(parts(1).split(' ').map(_.toDouble)))
	}.cache()
	
	// Building the model
	val stepSize = 0.01

	//Store the optimal value of the regularization parameter lambda 
	//and the associated coefficient of determination:
	var best_lambda = -1.0
	var min_error = 100000.0

	//Loop over a grid of values for lambda.
	val rang = List(3, 1, 0.5, 0.1, 0.01)
	for (lambda <- rang) {		
		//def kFold[T](rdd: RDD[T], numFolds: Int, seed: Int)(implicit arg0: ClassTag[T]): Array[(RDD[T], RDD[T])]
		//Return a k element array of pairs of RDDs with the first element of each pair containing the training data, 
		//a complement of the validation data and the second element, the validation data, containing a unique 1/kth of the data.
		var numberOfFolds = 5
		var seed = 21937
		val dataInFolds = MLUtils.kFold(parsedData, numberOfFolds, seed)

		val modelErrors = dataInFolds.map { case (train, test) => {
			//fit the model for the training set
			val model = LassoWithSGD.train(train, numIterations, stepSize, lambda) 

			// Evaluate model on test examples and compute test error
			val valuesAndPreds = test.map { point =>
				val prediction = model.predict(point.features)
				(point.label, prediction)
			}

			//calculate the mean squared error
			val MSE = valuesAndPreds.map{ case(v, p) => math.pow((v - p), 2) }.mean()

			//return the model and its test error
			(model, MSE)
		}}

		//Calculate the average error:
		var avgError = modelErrors.map(_._2).reduce(_ + _) / modelErrors.length

		//Here I update the optimal setting for lambda 
		//and the minimum for the mean squared error
		if(avgError < min_error){
			best_lambda = lambda;
			min_error = avgError;
		}
	}
	System.gc()
	//return a tuple with the file name, and the mean squared error for the optimal lambda:
	(fileName, min_error, best_lambda)
	//(fileName, min_error, best_lambda, sparsity)
}		

//Read a file that contains the names of all input files in separate lines.
var fileNames:Array[String] = Source.fromFile(path + "fileNames.txt").getLines.toArray
val numFiles = fileNames.length
val mean_squared_error_map = scala.collection.mutable.Map[String, Double]()
val best_lambda_map = scala.collection.mutable.Map[String, Double]()

//Iterate over the inputfiles:
for (fileName <- fileNames) {
   try {
   val (currentFileName, meanSquaredError, best_lambda) = runLasso(fileName, path, numIterations)
   mean_squared_error_map += (currentFileName.toString -> meanSquaredError.toDouble)
   best_lambda_map += (currentFileName.toString -> best_lambda.toDouble)
   } catch {
	case e : Throwable => println("Exception while processing files " + e.getMessage)
   }
}
//Create a new log file with results for each file:
val pw = new PrintWriter(new File("Results.txt" ))
//write header
pw.write("inputFile;MSE;lambda")
pw.println
for((key,value)<-mean_squared_error_map){
	best_lambda_map.get(key) match {
		case Some(lambda) => pw.write(s"$key,$value,$lambda")
		case None => pw.write(s"$key,$value,_")
	}
	pw.println
}
pw.close()
