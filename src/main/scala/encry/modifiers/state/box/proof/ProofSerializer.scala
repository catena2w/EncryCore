package encry.modifiers.state.box.proof

import scorex.core.serialization.Serializer

import scala.util.{Failure, Try}

object ProofSerializer extends Serializer[Proof] {

  override def toBytes(obj: Proof): Array[Byte] = obj match {
    case sig: Signature25519 =>
      Signature25519.TypeId +: Signature25519Serializer.toBytes(sig)
    case m => throw new Error(s"Serialization for unknown modifier: ${m.json.noSpaces}")
  }

  override def parseBytes(bytes: Array[Byte]): Try[Proof] = Try(bytes.head).flatMap {
    case Signature25519.`TypeId` =>
      Signature25519Serializer.parseBytes(bytes.tail)
    case m =>
      Failure(new Error(s"Deserialization for unknown type byte: $m"))
  }
}
