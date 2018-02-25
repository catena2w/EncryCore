package encry.cli

import akka.actor.{Actor, ActorRef}
import encry.cli.ConsolePromptListener.StartListening
import encry.cli.commands._
import encry.settings.EncryAppSettings
import scorex.core.utils.ScorexLogging

import scala.collection.mutable
import scala.io.StdIn

case class ConsolePromptListener(actors: mutable.HashMap[String, ActorRef],
                                 nodeViewHolderRef: ActorRef,
                                 settings: EncryAppSettings) extends Actor with ScorexLogging {

  val prompt = "$> "

  val commands: mutable.HashMap[String,mutable.HashMap[String, (Command, ActorRef)]] = mutable.HashMap.empty

  commands.update("node", mutable.HashMap(
    "-shutdown" -> (NodeShutdown, actorRef("NodeViewHolderRef")),
    "-reboot" -> (NodeReboot, actorRef("NodeViewHolderRef"))
  ))

  commands.update("app", mutable.HashMap(
    "-help" -> (Help, actorRef("NodeViewHolderRef"))
  ))

  commands.update("pki", mutable.HashMap(
    "-addPubKeyInfo" -> (AddPubKeyInfo, actorRef("NodeViewHolderRef"))
  ))

  commands.update("wallet", mutable.HashMap(
    "-addrs" -> (PrintMyAddrs, actorRef("NodeViewHolderRef")),
    "-addKey" -> (AddKey, actorRef("NodeViewHolderRef")),
    "-init" -> (InitKeyStorage, actorRef("NodeViewHolderRef")),
    "-pubKeys" -> (PrintPubKeys, actorRef("NodeViewHolderRef")),
    "-balance" -> (GetBalance, actorRef("NodeViewHolderRef")),
    "-transfer" -> (Transfer, actorRef("NodeViewHolderRef"))
  ))

  override def receive: Receive = {

    case StartListening =>
      Iterator.continually(StdIn.readLine(prompt)).takeWhile(!_.equals("quit")).foreach { input =>
        commands.get(parseCommand(input).head) match {
          case Some(value) =>
            parseCommand(input).slice(1, parseCommand(input).length).foreach { command =>
              value.get(command.split("=").head) match {
                case Some(cmd) =>
                  println(cmd._1.execute(cmd._2, command.split("="), settings).map(_.msg).getOrElse(""))
                case None =>
                  println("Unsupported command. Type 'app -help' to get commands list")
              }
          }
          case None =>
            println("Unsupported command. Type 'app -help' to get commands list")
        }
      }
  }

  private def parseCommand(command: String): Seq[String] = {
    val commandsSeq = command.split(" ").toSeq
    commandsSeq
  }

  private def actorRef(actorName: String): ActorRef = actors.getOrElse(actorName, nodeViewHolderRef)

}

object ConsolePromptListener {

  case object StartListening
}
