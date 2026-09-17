import discord
from discord import app_commands

STAFF_ROLE_NAMES = {"관리", "운영"}


def is_staff_member(member: discord.Member) -> bool:
    return any(role.name in STAFF_ROLE_NAMES for role in member.roles)


def staff_only():
    async def predicate(interaction: discord.Interaction) -> bool:
        if isinstance(interaction.user, discord.Member) and is_staff_member(interaction.user):
            return True
        raise app_commands.CheckFailure("이 명령어는 관리자(@관리 / @운영 역할)만 사용할 수 있습니다.")

    return app_commands.check(predicate)
