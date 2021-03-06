package com.acxiom.pipeline

import com.acxiom.pipeline.audits.{AuditType, ExecutionAudit}
import com.acxiom.pipeline.flow.SplitStepException
import org.apache.log4j.Logger
import org.json4s.ext.EnumNameSerializer
import org.json4s.native.{JsonParser, Serialization}
import org.json4s.{DefaultFormats, Formats}
import java.util.Date

import org.apache.spark.scheduler._

import scala.collection.mutable

object PipelineListener {
  def apply(): PipelineListener = DefaultPipelineListener()
}

case class DefaultPipelineListener() extends SparkPipelineListener

trait SparkPipelineListener extends SparkListener with PipelineListener {
  private var currentExecutionInfo: Option[PipelineExecutionInfo] = None
  private val applicationStats: ApplicationStats = ApplicationStats(mutable.Map())
  private val sparkSettingToReport: List[String] = List(
    "spark.kryoserializer.buffer.max", "spark.driver.memory", "spark.executor.memory", "spark.sql.shuffle.partitions",
    "spark.driver.cores", "spark.executor.cores", "spark.default.parallelism", "spark.driver.memoryOverhead",
    "spark.executor.memoryOverhead"
  )


  override def executionStarted(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    val defaultContext = pipelineContext.setRootAuditMetric(Constants.SPARK_SETTINGS, this.getSparkSettingsForAudit(pipelineContext))
    val finalContext = if (pipelineContext.getGlobalAs[Boolean]("includeAllSparkSettingsInAudit").contains(true)) {
      defaultContext.setRootAuditMetric("test-sparkContext", pipelineContext.sparkSession.get.sparkContext.getConf.getAll)
    } else { defaultContext }

    applicationStats.reset()
    Some(finalContext)
  }

  override def executionFinished(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    super.executionFinished(pipelines, pipelineContext)
  }

  override def executionStopped(pipelines: List[Pipeline], pipelineContext: PipelineContext): Unit = {
    super.executionStopped(pipelines, pipelineContext)
  }

  override def pipelineStarted(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    this.currentExecutionInfo = Some(pipelineContext.getPipelineExecutionInfo)
    None
  }

  override def pipelineFinished(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    super.pipelineFinished(pipeline, pipelineContext)
    None
  }

  override def pipelineStepStarted(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    this.currentExecutionInfo = Some(pipelineContext.getPipelineExecutionInfo)
    super.pipelineStepStarted(pipeline, step, pipelineContext)
    None
  }

  override def pipelineStepFinished(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    val newContext = if (pipeline.id.isDefined && step.id.isDefined) {
      val groupId = pipelineContext.getPipelineExecutionInfo.groupId
      pipelineContext.setStepMetric(
        pipeline.id.get, step.id.get, groupId, Constants.SPARK_METRICS, this.applicationStats.getSummary(pipeline.id, step.id, groupId)
      )
    } else {
      pipelineContext
    }
    super.pipelineStepFinished(pipeline, step, newContext)
    Some(newContext)
  }

  override def onJobStart(jobStart: SparkListenerJobStart): scala.Unit = {
    if (this.currentExecutionInfo.isDefined) {
      this.applicationStats.startJob(jobStart, this.currentExecutionInfo.get)
    }
  }

  override def onJobEnd(jobEnd: SparkListenerJobEnd): scala.Unit = {
    this.applicationStats.endJob(jobEnd)
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
    this.applicationStats.endStage(stageCompleted)
  }

  def getSparkSettingsForAudit(pipelineContext: PipelineContext): Map[String, Any] = {
    if (pipelineContext.sparkSession.isEmpty) {
      Map()
    } else {
      this.sparkSettingToReport.map(s => {
        val setting = pipelineContext.sparkSession.get.sparkContext.getConf.getOption(s)
        s -> setting.getOrElse("")
      }).toMap
    }
  }
}

trait PipelineListener {
  implicit val formats: Formats = DefaultFormats +
    new EnumNameSerializer(AuditType)
  private val logger = Logger.getLogger(getClass)

  def executionStarted(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    logger.info(s"Starting execution of pipelines ${pipelines.map(p => p.name.getOrElse(p.id.getOrElse("")))}")
    None
  }

  def executionFinished(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    logger.info(s"Finished execution of pipelines ${pipelines.map(p => p.name.getOrElse(p.id.getOrElse("")))}")
    logger.info(s"Execution Audit ${Serialization.write(pipelineContext.rootAudit)}")
    None
  }

  def executionStopped(pipelines: List[Pipeline], pipelineContext: PipelineContext): Unit = {
    logger.info(s"Stopping execution of pipelines. Completed: ${pipelines.map(p => p.name.getOrElse(p.id.getOrElse(""))).mkString(",")}")
    logger.info(s"Execution Audit ${Serialization.write(pipelineContext.rootAudit)}")
  }

  def pipelineStarted(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    logger.info(s"Starting pipeline ${pipeline.name.getOrElse(pipeline.id.getOrElse(""))}")
    None
  }

  def pipelineFinished(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    logger.info(s"Finished pipeline ${pipeline.name.getOrElse(pipeline.id.getOrElse(""))}")
    None
  }

  def pipelineStepStarted(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    logger.info(s"Starting step ${step.displayName.getOrElse(step.id.getOrElse(""))} of pipeline ${pipeline.name.getOrElse(pipeline.id.getOrElse(""))}")
    None
  }

  def pipelineStepFinished(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    logger.info(s"Finished step ${step.displayName.getOrElse(step.id.getOrElse(""))} of pipeline ${pipeline.name.getOrElse(pipeline.id.getOrElse(""))}")
    None
  }

  def registerStepException(exception: PipelineStepException, pipelineContext: PipelineContext): Unit = {
    // Base implementation does nothing
  }
}


trait EventBasedPipelineListener extends SparkPipelineListener {
  def key: String
  def credentialName: String
  def credentialProvider: CredentialProvider

  def generateExecutionMessage(event: String, pipelines: List[Pipeline]): String = {
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipelines" -> pipelines.map(pipeline => EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse("")))
    ))
  }

  def generateAuditMessage(event: String, audit: ExecutionAudit): String = {
    // Must cast to Long or it won't compile
    val duration = audit.end.getOrElse(Constants.ZERO).asInstanceOf[Long] - audit.start
    val auditString = Serialization.write(audit)
    val auditMap = JsonParser.parse(auditString).extract[Map[String, Any]]
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "duration" -> duration,
      "audit" -> auditMap))
  }

  def generatePipelineMessage(event: String, pipeline: Pipeline): String = {
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipeline" -> EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse(""))
    ))
  }

  def generatePipelineStepMessage(event: String, pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): String = {
    pipelineContext.getPipelineExecutionInfo.groupId
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipeline" -> EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse("")),
      "step" -> EventPipelineStepRecord(step.id.getOrElse(""), step.stepId.getOrElse(""),
        pipelineContext.getPipelineExecutionInfo.groupId.getOrElse(""))
    ))
  }

  def generateExceptionMessage(event: String, exception: PipelineStepException, pipelineContext: PipelineContext): String = {
    val executionInfo = pipelineContext.getPipelineExecutionInfo
    val messageList: MessageLists = exception match {
      case fe: ForkedPipelineStepException => fe.exceptions
        .foldLeft(MessageLists(List[String](), List[Array[StackTraceElement]]()))((t, e) =>
          MessageLists(t.messages :+ e._2.getMessage, t.stacks :+ e._2.getStackTrace))
      case se: SplitStepException => se.exceptions
        .foldLeft(MessageLists(List[String](), List[Array[StackTraceElement]]()))((t, e) =>
          MessageLists(t.messages :+ e._2.getMessage, t.stacks :+ e._2.getStackTrace))
      case p: PauseException => MessageLists(List(s"Paused: ${p.getMessage}"), List())
      case _ => MessageLists(List(exception.getMessage), List(exception.getStackTrace))
    }
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "executionId" -> executionInfo.executionId.getOrElse(""),
      "pipelineId" -> executionInfo.pipelineId.getOrElse(""),
      "stepId" -> executionInfo.stepId.getOrElse(""),
      "groupId" -> executionInfo.groupId.getOrElse(""),
      "messages" -> messageList.messages,
      "stacks" -> messageList.stacks
    ))
  }
}


case class SessionVariables(executionComplete: Boolean = false, currentPipeline: Option[Pipeline] = None, currentStep: Option[PipelineStep] = None)
case class EventPipelineRecord(id: String, name: String)
case class EventPipelineStepRecord(id: String, stepId: String, group: String)
case class MessageLists(messages: List[String], stacks: List[Array[StackTraceElement]])
case class CombinedPipelineListener(listeners: List[PipelineListener]) extends PipelineListener {
  override def executionStarted(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.executionStarted(pipelines, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def executionFinished(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.executionFinished(pipelines, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def executionStopped(pipelines: List[Pipeline], pipelineContext: PipelineContext): Unit = {
    listeners.foreach(_.executionStopped(pipelines, pipelineContext))
  }

  override def pipelineStarted(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStarted(pipeline, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineFinished(pipeline: Pipeline, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineFinished(pipeline, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineStepStarted(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStepStarted(pipeline, step, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineStepFinished(pipeline: Pipeline, step: PipelineStep, pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStepFinished(pipeline, step, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def registerStepException(exception: PipelineStepException, pipelineContext: PipelineContext): Unit = {
    listeners.foreach(_.registerStepException(exception, pipelineContext))
  }

  private def handleContext(updatedCtx: Option[PipelineContext], pipelineContext: PipelineContext): PipelineContext = {
    if (updatedCtx.isDefined) {
      updatedCtx.get
    } else {
      pipelineContext
    }
  }
}
