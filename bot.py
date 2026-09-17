import logging
import os

import discord
from discord.ext import commands
from dotenv import load_dotenv

load_dotenv()

logging.basicConfig(level=logging.INFO)

INTENTS = discord.Intents.default()
INTENTS.members = True

INITIAL_COGS = [
    "cogs.join_application",
]


class CHUBot(commands.Bot):
    def __init__(self):
        super().__init__(command_prefix="!", intents=INTENTS)

    async def setup_hook(self):
        for cog in INITIAL_COGS:
            await self.load_extension(cog)
        await self.tree.sync()


bot = CHUBot()


@bot.event
async def on_ready():
    print(f"Logged in as {bot.user} (ID: {bot.user.id})")


def main():
    token = os.environ.get("DISCORD_BOT_TOKEN")
    if not token:
        raise RuntimeError("DISCORD_BOT_TOKEN 환경변수가 설정되지 않았습니다.")
    bot.run(token)


if __name__ == "__main__":
    main()
