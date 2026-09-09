package com.allen.questionbank;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * 架构守护测试：把"模块化单体的边界"从口头约定变成可执行规则，CI 每次构建强制执行。
 *
 * 规则对应真实架构决策：
 * - 跨域 Repository 禁用：practice 想读题库数据必须走 bank.BankService——模块化单体最容易被
 *   侵蚀的边界（图省事直接注入别人的 Repository，领域边界就名存实亡）
 * - common 不反向依赖业务域：基础设施是供方不是需方
 * - Controller 不直接触 Repository：取数必须过 Service（事务边界与业务规则只在一处）
 * - 构造器注入、SLF4J：统一风格由机器守护，不靠 code review 自觉
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.allen.questionbank");
    }

    @Test
    void crossDomainRepositoryAccessIsForbidden() {
        ArchRule rule = noClasses().that().resideInAPackage("..questionbank.practice..")
                .should().dependOnClassesThat()
                .resideInAPackage("..questionbank.bank..")
                .andShould().haveSimpleNameEndingWith("Repository");
        rule.check(importedClasses);
    }

    @Test
    void commonInfrastructureDoesNotDependOnBusinessDomains() {
        ArchRule rule = noClasses().that().resideInAPackage("..questionbank.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..questionbank.practice..", "..questionbank.bank..", "..questionbank.auth..");
        rule.check(importedClasses);
    }

    @Test
    void controllersDoNotTouchRepositoriesDirectly() {
        ArchRule rule = noClasses().that().resideInAPackage("..questionbank..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        rule.check(importedClasses);
    }

    @Test
    void constructorInjectionOnly() {
        ArchRule rule = noFields().should().beAnnotatedWith(Autowired.class);
        rule.check(importedClasses);
    }

    @Test
    void noSystemOutOrJavaUtilLogging() {
        ArchRule noSysOut = noClasses()
                .should().callMethod(java.lang.System.class, "println").orShould()
                .callMethod(java.lang.System.class, "err");
        noSysOut.check(importedClasses);

        ArchRule noJul = noClasses()
                .should().dependOnClassesThat().belongToAnyOf(java.util.logging.Logger.class);
        noJul.check(importedClasses);
    }
}
