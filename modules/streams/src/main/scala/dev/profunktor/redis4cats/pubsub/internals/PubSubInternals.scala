/*
 * Copyright 2018-2021 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.redis4cats.pubsub.internals

import cats.effect.kernel.{ Async, Ref, Resource, Sync }
import cats.effect.std.Dispatcher
import cats.syntax.all._
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.data.RedisPattern
import dev.profunktor.redis4cats.data.RedisPatternEvent
import dev.profunktor.redis4cats.effect.Log
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.{ RedisPubSubListener, StatefulRedisPubSubConnection }
import io.lettuce.core.pubsub.RedisPubSubAdapter
import cats.effect.kernel.Deferred

object PubSubInternals {

  private[redis4cats] def channelListener[F[_]: Async, K, V](
      channel: RedisChannel[K],
      topic: Topic[F, Option[V]],
      dispatcher: Dispatcher[F]
  ): RedisPubSubListener[K, V] =
    new RedisPubSubAdapter[K, V] {
      override def message(ch: K, msg: V): Unit =
        if (ch == channel.underlying) {
          dispatcher.unsafeRunSync(topic.publish1(Option(msg)).void)
        }
      override def message(pattern: K, channel: K, message: V): Unit = this.message(channel, message)
    }
  private[redis4cats] def patternListener[F[_]: Async, K, V](
      redisPattern: RedisPattern[K],
      topic: Topic[F, Option[RedisPatternEvent[K, V]]],
      dispatcher: Dispatcher[F],
      hasSubscribed: Deferred[F, Unit]
  ): RedisPubSubListener[K, V] =
    new RedisPubSubAdapter[K, V] {
      override def message(pattern: K, channel: K, message: V): Unit =
        if (pattern == redisPattern.underlying) {
          dispatcher.unsafeRunSync(topic.publish1(Option(RedisPatternEvent(pattern, channel, message))).void)
        }

      override def psubscribed(pattern: K, count: Long): Unit =
        if (pattern == redisPattern.underlying) {
          println(s"psubscribed $pattern")
          dispatcher.unsafeRunSync(hasSubscribed.complete(()).void)
          println(s"Completed!")
          dispatcher.unsafeRunSync(hasSubscribed.get)
          println(s"got it!: $hasSubscribed ${hasSubscribed.hashCode()}")
        } else {
          println(s"psubscribed: No match for $pattern")
        }

      override def subscribed(channel: K, count: Long): Unit =
        println(s"subscribed $channel")
    }

  private[redis4cats] def channel[F[_]: Async: Log, K, V](
      state: Ref[F, PubSubState[F, K, V]],
      subConnection: StatefulRedisPubSubConnection[K, V]
  ): GetOrCreateTopicListener[F, K, V] = { channel => st =>
    st.channels
      .get(channel.underlying)
      .fold {
        for {
          dispatcher <- Dispatcher[F]
          topic <- Resource.eval(Topic[F, Option[V]])
          _ <- Resource.eval(Log[F].info(s"Creating listener for channel: $channel"))
          listener = channelListener(channel, topic, dispatcher)
          _ <- Resource.make {
                Sync[F].delay(subConnection.addListener(listener)) *>
                  state.update(s => s.copy(channels = s.channels.updated(channel.underlying, topic)))
              } { _ =>
                Sync[F].delay(subConnection.removeListener(listener)) *>
                  state.update(s => s.copy(channels = s.channels - channel.underlying))
              }
        } yield topic
      }(Resource.pure)
  }

  private[redis4cats] def pattern[F[_]: Async: Log, K, V](
      state: Ref[F, PubSubState[F, K, V]],
      subConnection: StatefulRedisPubSubConnection[K, V],
      hasSubscribed: Deferred[F, Unit]
  ): GetOrCreatePatternListener[F, K, V] = { channel => st =>
    st.patterns
      .get(channel.underlying)
      .fold {
        for {
          dispatcher <- Dispatcher[F]
          topic <- Resource.eval(Topic[F, Option[RedisPatternEvent[K, V]]])
          _ <- Resource.eval(Log[F].info(s"Creating listener for pattern: $channel"))
          listener = patternListener(channel, topic, dispatcher, hasSubscribed)
          acquisition = for {
            _ <- Sync[F].delay(subConnection.addListener(listener))
            _ <- state.update(s => s.copy(patterns = s.patterns.updated(channel.underlying, topic)))
          } yield ()
          release = for {
            _ <- Sync[F].delay(subConnection.removeListener(listener))
            _ <- state.update(s => s.copy(patterns = s.patterns - channel.underlying))
          } yield ()
          _ <- Resource.make(acquisition)(_ => release)
        } yield topic
      }(Resource.pure)
  }
}
