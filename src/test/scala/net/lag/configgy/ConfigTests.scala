package net.lag.configgy

import java.io.{File, FileOutputStream}

import sorg.testing._


object ConfigTests extends Tests {
    override def testName = "ConfigTests"


    class FakeSubscriber extends Subscriber {
        def validate(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = { }
        def commit(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = { }
    }


    // remembers the before & after config nodes when committing a change
    class MemorySubscriber extends Subscriber {
        var used = false
        var savedCurrent: Option[AttributeMap] = None
        var savedReplacement: Option[AttributeMap] = None

        def validate(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = { }
        def commit(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = {
            used = true
            savedCurrent = current
            savedReplacement = replacement
        }
    }


    // refuses any change to its node.
    class AngrySubscriber extends Subscriber {
        def validate(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = throw new ValidationException("no way!")
        def commit(current: Option[AttributeMap], replacement: Option[AttributeMap]): Unit = { }
    }


    test("subscribe") {
        val c = new Config
        var id = c.subscribe("alpha.beta.gamma", new FakeSubscriber)

        expect("subs=0 { alpha=0 { beta=0 { gamma=1 } } }") {
            c.debugSubscribers
        }

        c.unsubscribe(id)
        expect("subs=0 { alpha=0 { beta=0 { gamma=0 } } }") {
            c.debugSubscribers
        }

        id = c.subscribe("alpha.beta") { (attr: Option[AttributeMap]) => Console.println("hello") }

        expect("subs=0 { alpha=0 { beta=1 { gamma=0 } } }") {
            c.debugSubscribers
        }

        c.unsubscribe(id)
        expect("subs=0 { alpha=0 { beta=0 { gamma=0 } } }") {
            c.debugSubscribers
        }
    }

    test("validate called") {
        val c = new Config
        c("alpha.beta.gamma") = "hello"

        var checked = false
        c.subscribe("alpha.beta") { (attr: Option[AttributeMap]) => checked = true }
        expect(false) { checked }
        c("alpha.beta.delta") = "goodbye"
        expect(true) { checked }
    }

    test("validate has old & new") {
        val c = new Config
        c("alpha.beta.gamma") = "hello"

        val sub = new MemorySubscriber
        c.subscribe("alpha.beta", sub)
        expect(false) { sub.used }

        c("alpha.beta.delta") = "goodbye"
        expect(true) { sub.used }
        expect("{alpha.beta: gamma=\"hello\" }") { sub.savedCurrent.get.toString }
        expect("{alpha.beta: delta=\"goodbye\" gamma=\"hello\" }") { sub.savedReplacement.get.toString }
        expect("{: alpha={alpha: beta={alpha.beta: delta=\"goodbye\" gamma=\"hello\" } } }") { c.toString }

        c("alpha.beta.gamma") = "gutentag"
        expect("{alpha.beta: delta=\"goodbye\" gamma=\"hello\" }") { sub.savedCurrent.get.toString }
        expect("{alpha.beta: delta=\"goodbye\" gamma=\"gutentag\" }") { sub.savedReplacement.get.toString }
        expect("{: alpha={alpha: beta={alpha.beta: delta=\"goodbye\" gamma=\"gutentag\" } } }") { c.toString }
    }

    test("reject change") {
        val c = new Config
        c("alpha.beta.gamma") = "hello"

        c.subscribe("alpha.beta", new AngrySubscriber)
        expectThrow(classOf[ValidationException]) {
            c("alpha.beta.gamma") = "gutentag"
        }
        c("alpha.giraffe") = "tall!"
        expect("{: alpha={alpha: beta={alpha.beta: gamma=\"hello\" } giraffe=\"tall!\" } }") { c.toString }
    }

    test("lots of subscribers") {
        val c = new Config
        c("alpha.beta.gamma") = "hello"
        c("alpha.giraffe") = "tall!"
        c("forest.fires.are") = "bad"

        val rootsub = new MemorySubscriber
        c.subscribe(rootsub)
        val firesub = new AngrySubscriber
        c.subscribe("forest.fires", firesub)
        val betasub = new MemorySubscriber
        c.subscribe("alpha.beta", betasub)

        c("unrelated") = 39
        expect(true) { rootsub.used }
        expect(false) { betasub.used }
        expect("{: alpha={alpha: beta={alpha.beta: gamma=\"hello\" } giraffe=\"tall!\" } forest={forest: fires={forest.fires: are=\"bad\" } } }") { rootsub.savedCurrent.get.toString }
        expect("{: alpha={alpha: beta={alpha.beta: gamma=\"hello\" } giraffe=\"tall!\" } forest={forest: fires={forest.fires: are=\"bad\" } } unrelated=\"39\" }") { rootsub.savedReplacement.get.toString }

        rootsub.used = false
        c("forest.matches") = false
        expect(true) { rootsub.used }
        expect(false) { betasub.used }
        expect("{forest: fires={forest.fires: are=\"bad\" } matches=\"false\" }") { c.getAttributes("forest").get.toString }

        expectThrow(classOf[ValidationException]) {
            c.remove("forest")
        }

        rootsub.used = false
        betasub.used = false
        c("alpha.beta.gamma") = "goodbye"
        expect(true) { rootsub.used }
        expect(true) { betasub.used }
        expect("{alpha.beta: gamma=\"hello\" }") { betasub.savedCurrent.get.toString }
        expect("{alpha.beta: gamma=\"goodbye\" }") { betasub.savedReplacement.get.toString }
    }

    test("relative include") {
        withTempFolder {
            val inner = new File(folderName, "inner")
            inner.mkdir

            val data1 = "fruit = 17\ninclude \"inner/punch.conf\"\n"
            val f1 = new FileOutputStream(new File(folderName, "fruit.conf"))
            f1.write(data1.getBytes)
            f1.close
            val data2 = "punch = 23\n"
            val f2 = new FileOutputStream(new File(inner, "punch.conf"))
            f2.write(data2.getBytes)
            f2.close

            val c = new Config
            c.loadFile(folderName, "fruit.conf")
            expect("{: fruit=\"17\" punch=\"23\" }") { c.toString }
        }
    }

    test("include from resource") {
        /* kinda cheaty: we know the current folder is the project root,
         * so we can stuff something in build-test/ briefly to get it to
         * appear in the classpath.
         */
        val tempFilename = new File(new File(".").getAbsolutePath, "target/test-classes/happy.conf")
        try {
            val data1 = "commie = 501\n"
            val f1 = new FileOutputStream(tempFilename)
            f1.write(data1.getBytes)
            f1.close

            val c = new Config
            c.importer = new ResourceImporter
            c.load("include \"happy.conf\"\n")
            expect("{: commie=\"501\" }") { c.toString }
        } finally {
            tempFilename.delete
        }
    }
}