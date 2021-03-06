package encry.local.miner

import encry.settings.EncryAppSettings
import akka.actor.{Actor, ActorRef, Props}
import encry.consensus.{PowCandidateBlock, PowConsensus}
import encry.local.miner.EncryMiningWorker.MineBlock
import encry.view.state.StateMode
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class EncryMiningWorker(settings: EncryAppSettings,
                        viewHolderRef: ActorRef,
                        initialCandidate: PowCandidateBlock) extends Actor with ScorexLogging {

  private val consensus = PowConsensus

  private var candidate: PowCandidateBlock = initialCandidate

  override def preStart(): Unit = {
    log.info("Booting new mining worker")
    context.system.scheduler.scheduleOnce(settings.nodeSettings.miningDelay) (self ! MineBlock(Random.nextLong()))
  }

  override def receive: Receive = {
    case newCandidate: PowCandidateBlock =>
      candidate = newCandidate

    case MineBlock(nonce) =>
      consensus.verifyCandidate(candidate, nonce) match {
        case Some(block) =>
          log.info(s"New block found: $block")

          viewHolderRef ! LocallyGeneratedModifier(block.header)
          viewHolderRef ! LocallyGeneratedModifier(block.payload)
          if (settings.nodeSettings.stateMode == StateMode.Digest) {
            block.adProofsOpt.foreach { adp =>
              viewHolderRef ! LocallyGeneratedModifier(adp)
            }
          }
          context.system.scheduler.scheduleOnce(settings.nodeSettings.miningDelay) {
            self ! MineBlock(Random.nextLong())
          }
        case None =>
          self ! MineBlock(nonce + 1)
      }
  }
}

object EncryMiningWorker {

  case class MineBlock(nonce: Long)

  def props(ergoSettings: EncryAppSettings, viewHolderRef: ActorRef, startCandidate: PowCandidateBlock): Props = {
    Props(new EncryMiningWorker(ergoSettings, viewHolderRef, startCandidate))
  }
}
