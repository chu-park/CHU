from __future__ import annotations

import copy
from typing import Optional

import discord
from discord import app_commands
from discord.ext import commands

from utils import permissions, storage

DEFAULT_CONFIG = {
    "channel_id": None,
    "message_id": None,
    "title": "가입 신청서",
    "description": "아래 버튼을 눌러 가입 신청을 진행해주세요.",
    "questions": ["가입 동기를 알려주세요."],
    "review_channel_id": None,
    "approve_role_id": None,
    "pending": {},
}


def get_config(guild_id: int) -> dict:
    data = storage.load_guild_data(guild_id)
    config = copy.deepcopy(DEFAULT_CONFIG)
    config.update(data.get("join_application", {}))
    return config


def save_config(guild_id: int, config: dict) -> None:
    data = storage.load_guild_data(guild_id)
    data["join_application"] = config
    storage.save_guild_data(guild_id, data)


def build_recruitment_embed(config: dict) -> discord.Embed:
    return discord.Embed(
        title=config["title"],
        description=config["description"],
        color=discord.Color.blurple(),
    )


class JoinApplyView(discord.ui.View):
    def __init__(self, cog: "JoinApplicationCog"):
        super().__init__(timeout=None)
        self.cog = cog

    @discord.ui.button(label="신청하기", style=discord.ButtonStyle.primary, custom_id="join_app:apply")
    async def apply_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        await self.cog.handle_apply_click(interaction)


class ApplicationModal(discord.ui.Modal, title="가입 신청서"):
    def __init__(self, cog: "JoinApplicationCog", questions: list[str]):
        super().__init__()
        self.cog = cog
        self.question_inputs: list[tuple[str, discord.ui.TextInput]] = []
        for question in questions[:5]:
            text_input = discord.ui.TextInput(
                label=question[:45],
                style=discord.TextStyle.paragraph,
                required=True,
                max_length=1000,
            )
            self.add_item(text_input)
            self.question_inputs.append((question, text_input))

    async def on_submit(self, interaction: discord.Interaction):
        answers = [(question, text_input.value) for question, text_input in self.question_inputs]
        await self.cog.handle_application_submit(interaction, answers)


class ReviewView(discord.ui.View):
    def __init__(self, cog: "JoinApplicationCog"):
        super().__init__(timeout=None)
        self.cog = cog

    @discord.ui.button(label="승인", style=discord.ButtonStyle.success, custom_id="join_app:approve")
    async def approve_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        await self.cog.handle_review_decision(interaction, approve=True)

    @discord.ui.button(label="거절", style=discord.ButtonStyle.danger, custom_id="join_app:reject")
    async def reject_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        await self.cog.handle_review_decision(interaction, approve=False)


class SettingsModal(discord.ui.Modal, title="가입신청서 내용 설정"):
    def __init__(self, cog: "JoinApplicationCog", guild_id: int, config: dict):
        super().__init__()
        self.cog = cog
        self.guild_id = guild_id
        self.title_input = discord.ui.TextInput(
            label="제목", default=config["title"], max_length=256
        )
        self.description_input = discord.ui.TextInput(
            label="설명",
            style=discord.TextStyle.paragraph,
            default=config["description"],
            max_length=1000,
            required=False,
        )
        self.questions_input = discord.ui.TextInput(
            label="질문 항목 (한 줄에 하나, 최대 5개)",
            style=discord.TextStyle.paragraph,
            default="\n".join(config["questions"]),
            max_length=500,
        )
        self.add_item(self.title_input)
        self.add_item(self.description_input)
        self.add_item(self.questions_input)

    async def on_submit(self, interaction: discord.Interaction):
        config = get_config(self.guild_id)
        config["title"] = self.title_input.value
        config["description"] = self.description_input.value or DEFAULT_CONFIG["description"]

        questions = [q.strip() for q in self.questions_input.value.splitlines() if q.strip()][:5]
        config["questions"] = questions or list(DEFAULT_CONFIG["questions"])
        save_config(self.guild_id, config)

        if config["channel_id"] and config["message_id"]:
            channel = interaction.guild.get_channel(config["channel_id"])
            if channel is not None:
                try:
                    message = await channel.fetch_message(config["message_id"])
                    await message.edit(embed=build_recruitment_embed(config))
                except discord.HTTPException:
                    pass

        await interaction.response.send_message("가입신청서 내용을 업데이트했습니다.", ephemeral=True)


class SettingsEditView(discord.ui.View):
    def __init__(self, cog: "JoinApplicationCog", guild_id: int):
        super().__init__(timeout=180)
        self.cog = cog
        self.guild_id = guild_id

    @discord.ui.button(label="신청서 항목 편집", style=discord.ButtonStyle.secondary)
    async def edit_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        config = get_config(self.guild_id)
        await interaction.response.send_modal(SettingsModal(self.cog, self.guild_id, config))


class JoinApplicationCog(commands.Cog):
    def __init__(self, bot: commands.Bot):
        self.bot = bot

    async def cog_load(self):
        self.bot.add_view(JoinApplyView(self))
        self.bot.add_view(ReviewView(self))

    async def cog_app_command_error(
        self, interaction: discord.Interaction, error: app_commands.AppCommandError
    ):
        if isinstance(error, app_commands.CheckFailure):
            message = str(error) or "이 명령어를 사용할 권한이 없습니다."
            if interaction.response.is_done():
                await interaction.followup.send(message, ephemeral=True)
            else:
                await interaction.response.send_message(message, ephemeral=True)
            return
        raise error

    # ---------- 신청하기 흐름 ----------

    async def handle_apply_click(self, interaction: discord.Interaction):
        guild = interaction.guild
        config = get_config(guild.id)
        user_id = str(interaction.user.id)

        if user_id in config["pending"]:
            await interaction.response.send_message(
                "이미 처리 대기 중인 신청이 있습니다. 처리가 완료될 때까지 기다려주세요.",
                ephemeral=True,
            )
            return

        await interaction.response.send_modal(ApplicationModal(self, config["questions"]))

    async def handle_application_submit(
        self, interaction: discord.Interaction, answers: list[tuple[str, str]]
    ):
        guild = interaction.guild
        config = get_config(guild.id)
        user_id = str(interaction.user.id)

        if user_id in config["pending"]:
            await interaction.response.send_message(
                "이미 처리 대기 중인 신청이 있습니다. 처리가 완료될 때까지 기다려주세요.",
                ephemeral=True,
            )
            return

        if not config["review_channel_id"]:
            await interaction.response.send_message(
                "관리자가 아직 승인채널을 설정하지 않았습니다. 관리자에게 문의해주세요.",
                ephemeral=True,
            )
            return

        review_channel = guild.get_channel(config["review_channel_id"])
        if review_channel is None:
            await interaction.response.send_message(
                "설정된 승인채널을 찾을 수 없습니다. 관리자에게 문의해주세요.",
                ephemeral=True,
            )
            return

        embed = discord.Embed(
            title="새 가입 신청",
            description=f"{interaction.user.mention} 님의 신청입니다.",
            color=discord.Color.gold(),
        )
        embed.set_author(name=str(interaction.user), icon_url=interaction.user.display_avatar.url)
        for question, answer in answers:
            embed.add_field(name=question, value=answer[:1024] or "-", inline=False)
        embed.set_footer(text=f"user_id:{interaction.user.id}")

        message = await review_channel.send(embed=embed, view=ReviewView(self))

        config["pending"][user_id] = {
            "review_channel_id": review_channel.id,
            "review_message_id": message.id,
        }
        save_config(guild.id, config)

        await interaction.response.send_message("가입 신청이 접수되었습니다. 처리 결과를 기다려주세요.", ephemeral=True)

    # ---------- 승인/거절 흐름 ----------

    async def handle_review_decision(self, interaction: discord.Interaction, approve: bool):
        if not isinstance(interaction.user, discord.Member) or not permissions.is_staff_member(
            interaction.user
        ):
            await interaction.response.send_message(
                "이 작업은 관리자(@관리 / @운영 역할)만 할 수 있습니다.", ephemeral=True
            )
            return

        guild = interaction.guild
        embed = interaction.message.embeds[0] if interaction.message.embeds else None
        if embed is None or not embed.footer or not embed.footer.text.startswith("user_id:"):
            await interaction.response.send_message("신청 정보를 확인할 수 없습니다.", ephemeral=True)
            return

        applicant_id = int(embed.footer.text.split(":", 1)[1])
        config = get_config(guild.id)
        pending_entry = config["pending"].pop(str(applicant_id), None)

        if pending_entry is None:
            await interaction.response.send_message("이미 처리된 신청입니다.", ephemeral=True)
            return

        applicant = guild.get_member(applicant_id)
        if applicant is None:
            try:
                applicant = await guild.fetch_member(applicant_id)
            except discord.NotFound:
                applicant = None

        result_text = "승인됨 ✅" if approve else "거절됨 ❌"
        embed.color = discord.Color.green() if approve else discord.Color.red()
        embed.add_field(name="처리 결과", value=f"{result_text} (처리자: {interaction.user.mention})", inline=False)

        if approve and applicant is not None and config["approve_role_id"]:
            role = guild.get_role(config["approve_role_id"])
            if role is not None:
                try:
                    await applicant.add_roles(role, reason="가입신청 승인")
                except discord.HTTPException:
                    pass

        if applicant is not None:
            dm_text = (
                "가입 신청이 승인되었습니다. 환영합니다!"
                if approve
                else "가입 신청이 거절되었습니다."
            )
            try:
                await applicant.send(dm_text)
            except discord.HTTPException:
                pass

        save_config(guild.id, config)

        disabled_view = ReviewView(self)
        for child in disabled_view.children:
            child.disabled = True

        await interaction.response.edit_message(embed=embed, view=disabled_view)

    # ---------- 슬래시 커맨드 ----------

    @app_commands.command(name="가입신청채팅방고정", description="현재 채널에 가입신청 임베드를 전송하고 고정합니다.")
    @app_commands.guild_only()
    @permissions.staff_only()
    async def pin_join_application(self, interaction: discord.Interaction):
        guild = interaction.guild
        config = get_config(guild.id)
        channel = interaction.channel

        if config["channel_id"] and config["message_id"]:
            old_channel = guild.get_channel(config["channel_id"])
            if old_channel is not None:
                try:
                    old_message = await old_channel.fetch_message(config["message_id"])
                    await old_message.unpin()
                    await old_message.delete()
                except discord.HTTPException:
                    pass

        message = await channel.send(embed=build_recruitment_embed(config), view=JoinApplyView(self))
        try:
            await message.pin()
        except discord.HTTPException:
            pass

        config["channel_id"] = channel.id
        config["message_id"] = message.id
        save_config(guild.id, config)

        await interaction.response.send_message("가입신청 메시지를 이 채널에 고정했습니다.", ephemeral=True)

    @app_commands.command(name="가입신청서설정", description="가입신청서 내용, 승인채널, 승인역할을 설정합니다.")
    @app_commands.describe(승인채널="신청서가 접수될 채널", 승인역할="승인 시 자동으로 부여할 역할")
    @app_commands.guild_only()
    @permissions.staff_only()
    async def configure_join_application(
        self,
        interaction: discord.Interaction,
        승인채널: Optional[discord.TextChannel] = None,
        승인역할: Optional[discord.Role] = None,
    ):
        guild = interaction.guild
        config = get_config(guild.id)
        changed = []

        if 승인채널 is not None:
            config["review_channel_id"] = 승인채널.id
            changed.append(f"승인채널 → {승인채널.mention}")
        if 승인역할 is not None:
            config["approve_role_id"] = 승인역할.id
            changed.append(f"승인역할 → {승인역할.mention}")
        if changed:
            save_config(guild.id, config)

        lines = ["## 가입신청서 설정"]
        if changed:
            lines.append("변경사항: " + ", ".join(changed))

        review_channel_text = (
            f"<#{config['review_channel_id']}>" if config["review_channel_id"] else "설정 안됨"
        )
        approve_role_text = (
            f"<@&{config['approve_role_id']}>" if config["approve_role_id"] else "설정 안됨"
        )
        lines.append(f"현재 승인채널: {review_channel_text}")
        lines.append(f"현재 승인역할: {approve_role_text}")
        lines.append("아래 버튼으로 신청서 제목/설명/질문 항목을 수정할 수 있습니다.")

        view = SettingsEditView(self, guild.id)
        await interaction.response.send_message("\n".join(lines), view=view, ephemeral=True)


async def setup(bot: commands.Bot):
    await bot.add_cog(JoinApplicationCog(bot))
