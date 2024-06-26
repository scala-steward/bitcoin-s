---
id: address-tagging
title: Address and UTXO tagging
---

```scala mdoc:invisible
import org.bitcoins.core.wallet.utxo._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.wallet.fee._
import org.bitcoins.core.currency._
import org.bitcoins.core.api.wallet.db.AccountDb
import org.bitcoins.wallet._

val ExampleAddressTag = UnknownAddressTag("name", "tagType")
val exampleAddress = BitcoinAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
val account: AccountDb = null
val destinations = Vector(TransactionOutput(Satoshis.one, exampleAddress.scriptPubKey))
case object wallet {
    // need to define these functions so we don't have to implement every function
    def fundRawTransaction(
              destinations: Vector[TransactionOutput],
              feeRate: FeeUnit,
              fromTagOpt: Option[AddressTag],
              markAsReserved: Boolean) = true

    def getNewAddress(tags: Vector[AddressTag]) = true

    def sendToAddress(
          address: BitcoinAddress,
          amount: CurrencyUnit,
          feeRate: FeeUnit,
          fromAccount: AccountDb,
          newTags: Vector[AddressTag]) = true
}
```

### Using AddressTags

The Bitcoin-S wallet allows you to give addresses, and their associated utxos,
a tag. These tags allow you to separate funds between utxos so you can query utxos,
and spend from them, based off of an AddressTag. The system also allows you to create
your own custom address tags, that will be enforced by the library.

An address tag consists of the tag name, and a tag type. We use a tag type so we can have
tag with the same name without complications.

To create an address with a tag you can use `getNewAddress` but pass in a `Vector[AddressTag]`.
It will add to the address tag database along with all the corresponding tags.

```scala mdoc:silent
wallet.getNewAddress(tags = Vector(ExampleAddressTag)) 
```

When sending with `sendToAddress` you can also a `Vector` of new `AddressTag`s that will be applied to the
resulting change outputs. Any tags of a different tag type not included in `newTag`s will also be applied to
the change outputs.

```scala mdoc:silent
wallet.sendToAddress(exampleAddress, Bitcoins(2), SatoshisPerVirtualByte.one, account, Vector(ExampleAddressTag)) 
```

Also, when sending you can use `fundRawTransaction` and use `fromTagOpt` to pass in an optional `AddressTag`,
this will use only utxos associated with the `AddressTag`.

```scala mdoc:silent
wallet.fundRawTransaction(
    destinations = destinations,
    feeRate = SatoshisPerVirtualByte.one,
    fromTagOpt = Some(ExampleAddressTag),
    markAsReserved = false)
```

### Creating your own AddressTags

You can create your own custom `AddressTag`s. This allows you to tag addresses and utxos in any way that your
application needs. To do this you are going to need to use `ExternalAddressTag`.  As an example we will create
`AddressTag`s for user specific funds.

We will need to define the tag type, then define the tag name for each tag, as well as a way to go to and
from a `String`. Then we define the actual tags, we are going to have a `Company`, `InsuranceFund`, and `UserId`
tags. We are going to make the `UserId` tag special, and allow it to take in any user id so we can have a huge
set of users but all with different ids.

```scala mdoc:silent
object UserIdTagType extends ExternalAddressTagType {
  override val typeName: String = "UserIdTag"
}

/** Allows to assign funds in a specific address to a user */
sealed trait UserIdTag extends ExternalAddressTag {
  override val tagType: AddressTagType = UserIdTagType
}

object UserIdTags extends AddressTagFactory[UserIdTag] {

  override val tagType: ExternalAddressTagType = UserIdTagType

  case object CompanyTagName extends ExternalAddressTagName {
    override def name: String = "Company"
  }

  case object InsuranceFundTagName extends ExternalAddressTagName {
    override def name: String = "InsuranceFund"
  }

  /** Funds that do not belong to any user and instead belong to the company */
  case object Company extends ExternalAddressTag with UserIdTag {
    override val tagName: ExternalAddressTagName = CompanyTagName
  }

  /** Funds in the company's insurance fund */
  case object InsuranceFund extends ExternalAddressTag with UserIdTag {
    override val tagName: ExternalAddressTagName = InsuranceFundTagName
  }

  /** Funds that are specific to an individual user */
  case class UserId(id: String) extends ExternalAddressTag with UserIdTag {
    override val tagName: ExternalAddressTagName = new ExternalAddressTagName {
        override def name: String = id
    }

    val uid = id.toLong
  }

  override val all: Vector[UserIdTag] = Vector(Company, InsuranceFund)

  override val tagNames: Vector[AddressTagName] = Vector(CompanyTagName, InsuranceFundTagName)

  override def fromStringOpt(str: String): Option[UserIdTag] = {
    all.find(tag => str.toLowerCase() == tag.toString.toLowerCase) match {
      case Some(tag) =>
        Some(tag)
      case None =>
        Some(UserId(str))
    }
  }

  override def fromString(str: String): UserIdTag = {
    fromStringOpt(str) match {
      case Some(tag) => tag
      case None => sys.error(s"Could not find tag=$str")
    }

  }

  def fromUID(uid: Long): UserIdTag = {
    UserId(uid.toString)
  }
}
```
