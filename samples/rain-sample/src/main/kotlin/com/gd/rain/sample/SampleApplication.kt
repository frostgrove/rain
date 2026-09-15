package com.gd.rain.sample

import com.gd.rain.boot.command.runRain
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.seed.SeedProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties

/**
 * A helpdesk. Agents sign in; tickets are listed, changed, closed and deleted; a language model drafts a ticket's
 * summary in the background; whoever watches a ticket sees its changes as they commit. One image runs as every
 * process of a deployment: `api`, `worker`, and the `migrate`, `seed` and `ticket-report` commands.
 */
@SpringBootApplication
@EnableConfigurationProperties(TicketProperties::class, SeedProperties::class)
class SampleApplication

fun main(args: Array<String>) {
    runRain<SampleApplication>(args)
}
