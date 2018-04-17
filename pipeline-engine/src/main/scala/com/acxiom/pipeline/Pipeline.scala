package com.acxiom.pipeline

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.CollectionAccumulator
import scala.collection.JavaConversions._

/**
  * Contains the a pipeline definition to be executed.
  *
  * @param id    The unique id of this pipeline.
  * @param name  The pipeline name used for logging and errors.
  * @param steps A list of steps to execute.
  */
case class Pipeline(id: Option[String] = None, name: Option[String] = None, steps: Option[List[PipelineStep]] = None)

/**
  * Global object that may be passed to step functions.
  *
  * @param sparkConf        The Spark Configuration Object.
  * @param sparkSession     The Spark Session Object.
  * @param globals          Contains all global objects.
  * @param security         The PipelineSecurityManager to use when processing steps
  * @param parameters       The pipeline parameters being used. Contains initial parameters as well as the result
  *                         of steps that have been processed.
  * @param stepPackages     The list of packages to consider when searching for step objects.
  * @param parameterMapper  Used to map parameters to step functions
  * @param pipelineListener Used to communicate progress through the pipeline
  * @param stepMessages     Used for logging messages from steps.
  */
case class PipelineContext(sparkConf: Option[SparkConf] = None,
                           sparkSession: Option[SparkSession] = None,
                           globals: Option[Map[String, Any]],
                           security: PipelineSecurityManager = PipelineSecurityManager(),
                           parameters: PipelineParameters,
                           stepPackages: Option[List[String]] = Some(List("com.acxiom.pipeline", "com.acxiom.pipeline.steps")),
                           parameterMapper: PipelineStepMapper = PipelineStepMapper(),
                           pipelineListener: Option[PipelineListener] = Some(DefaultPipelineListener()),
                           stepMessages: Option[CollectionAccumulator[PipelineStepMessage]]) {

  def getGlobalString(globalName: String): Option[String] = {
    if (this.globals.isDefined &&
      this.globals.get.contains(globalName)) {
      // TODO Expand to support other types
      this.globals.get(globalName) match {
        case str: String =>
          Some(str)
        case _: Option[_] =>
          this.globals.get(globalName).asInstanceOf[Option[String]]
        case _ =>
          None
      }
    } else {
      None
    }
  }

  /**
    * This function will add or update a single entry on the globals map.
    *
    * @param globalName  The name of the global property to set.
    * @param globalValue The value of the global property to set.
    * @return A new PipelineContext with an updated globals map.
    */
  def setGlobal(globalName: String, globalValue: java.io.Serializable): PipelineContext =
    this.copy(globals = Some(this.globals.getOrElse(Map[String, Any]()) + (globalName -> globalValue)))

  /**
    * This function will add or update a single entry on the globals map.
    *
    * @param globalName  The name of the global property to set.
    * @param globalValue The value of the global property to set.
    * @return A new PipelineContext with an updated globals map.
    */
  def setGlobal(globalName: String, globalValue: Serializable): PipelineContext =
    this.copy(globals = Some(this.globals.getOrElse(Map[String, Any]()) + (globalName -> globalValue)))

  /**
    * Adds a new PipelineStepMessage to the context
    *
    * @param message The message to add.
    */
  def addStepMessage(message: PipelineStepMessage): Unit = {
    if (stepMessages.isDefined) stepMessages.get.add(message)
  }

  /**
    * Returns a list of PipelineStepMessages.
    *
    * @return a list of PipelineStepMessages
    */
  def getStepMessages: Option[List[PipelineStepMessage]] = {
    if (stepMessages.isDefined) {
      Some(stepMessages.get.value.toList)
    } else {
      None
    }
  }

  def setParameterByPipelineId(pipelineId: String, name: String, parameter: Any): PipelineContext = {
    val params = parameters.setParameterByPipelineId(pipelineId, name, parameter)
    this.copy(parameters = params)
  }
}

case class PipelineParameter(pipelineId: String, parameters: Map[String, Any])

/**
  * Represents initial parameters for each pipeline as well as results from step execution.
  *
  * @param parameters An initial list of pipeline parameters
  */
case class PipelineParameters(parameters: List[PipelineParameter] = List()) {
  /**
    * Returns the PipelineParameter for the given pipeline id.
    *
    * @param pipelineId The id of the pipeline
    * @return An Option containing the parameter
    */
  def getParametersByPipelineId(pipelineId: String): Option[PipelineParameter] =
    parameters.find(p => p.pipelineId == pipelineId)

  /**
    * This will set a named parameter on for the provided pipeline id.
    *
    * @param pipelineId The id of the pipeline
    * @param name       The name of the parameter
    * @param parameter  The parameter value
    * @return A new copy of PipelineParameters
    */
  def setParameterByPipelineId(pipelineId: String, name: String, parameter: Any): PipelineParameters = {
    val param = getParametersByPipelineId(pipelineId)
    if (param.isDefined) {
      val p = param.get.copy(parameters = param.get.parameters + (name -> parameter))
      val updatedParameters = parameters.map(ps => if (ps.pipelineId == pipelineId) p else ps)
      this.copy(parameters = updatedParameters)
    } else {
      this
    }
  }
}
