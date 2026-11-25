package com.deepseekharness.app.skills;

import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.SkillLifecycle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 测试用：在包内构造技能目录，不触碰真实文件系统。 */
public final class SkillCatalogueFixture {
  private SkillCatalogueFixture() {}

  public static final String REMOVAL_SAMPLE = "dsha375-qa-r9-fs-cancel-02";

  public static byte[] removalSample() {
    String frontmatter =
        "---\nname: "
            + REMOVAL_SAMPLE
            + "\ndescription: Isolated build375 read/cancel fixture\n"
            + "disable-model-invocation: true\nuser-invocable: false\n---\n\n";
    String line = "Inert synthetic skill test text.\n";
    return (frontmatter + line.repeat((1_048_575 - frontmatter.length()) / line.length()) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  public static SkillImports.Catalogue withSource(byte[] source) throws IOException {
    SkillDocument document = SkillDocument.parse(source);
    return new SkillImports.Catalogue(
        List.of(new SkillImports.Entry(document.name + "/SKILL.md", document)), 0);
  }

  public static SkillImports.Catalogue of(int unreadable, String... names) throws IOException {
    return withStates(unreadable, 0, 0, names);
  }

  /** 末尾 disabled 个技能为已禁用；另附 trashed 个最近删除项（删除于 1 天前）。 */
  public static SkillImports.Catalogue withStates(
      int unreadable, int disabled, int trashed, String... names) throws IOException {
    List<SkillImports.Entry> entries = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      boolean enabled = i < names.length - disabled;
      String text =
          "---\nname: "
              + name
              + "\ndescription: Fixture skill "
              + name
              + " with a description long enough to wrap onto a second line on phones.\n---\n\n# "
              + name
              + "\nRun the documented steps.\n";
      entries.add(
          new SkillImports.Entry(
              name + (enabled ? "/SKILL.md" : "/SKILL.md.disabled"),
              SkillDocument.parse(text.getBytes(StandardCharsets.UTF_8)),
              enabled));
    }
    List<SkillLibrary.Item> trash = new ArrayList<>();
    long deleted = System.currentTimeMillis() - SkillLifecycle.DAY_MILLIS;
    for (int i = 0; i < trashed; i++) {
      String name = "deleted-skill-" + (i + 1);
      trash.add(
          new SkillLibrary.Item(
              java.util.UUID.randomUUID().toString(),
              SkillLifecycle.TrashMeta.of(name, name, SkillLifecycle.Kind.DIRECTORY, deleted),
              "Deleted fixture " + name,
              true));
    }
    return new SkillImports.Catalogue(entries, unreadable, trash);
  }
}
